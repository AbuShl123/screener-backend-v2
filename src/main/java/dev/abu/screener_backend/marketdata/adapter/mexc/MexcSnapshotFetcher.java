package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcDepthClient.BodyKind;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSnapshotProperties.MarketSnapshot;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeApiException;
import dev.abu.screener_backend.marketdata.spi.SnapshotFetcher;
import dev.abu.screener_backend.marketdata.spi.SnapshotOutcome;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Fetches depth snapshots for one MEXC venue: classifies each response before reporting it, and
 * paces every send at the venue's {@code request-interval}. One instance per venue, each with its
 * own clock and cooldown: spot depth does not draw from the futures window
 * ({@code external-docs/mexc/mexc-spot-depth-empirical.md} §4).
 *
 * <p><b>Classify before reporting.</b> A 2xx is not a snapshot until the venue's
 * {@link MexcDepthClient#classifyDepth} says so — futures throttles with an HTTP 200 whose envelope
 * says {@code success:false, code:510}. Classification never parses the levels.
 *
 * <table>
 *   <tr><th>Response</th><th>Report</th><th>Cooldown</th></tr>
 *   <tr><td>2xx, {@code OK}</td><td>delivered</td><td>—</td></tr>
 *   <tr><td>2xx, {@code THROTTLED} (futures {@code code:510})</td><td>failed</td><td>{@code throttle-cooldown}</td></tr>
 *   <tr><td>403 (Akamai WAF, HTML) or 429</td><td>failed</td><td>{@code waf-cooldown}</td></tr>
 *   <tr><td>anything else — other codes, unreadable body, other status, timeout</td><td>failed</td><td>—</td></tr>
 * </table>
 *
 * <p><b>One send clock, not one per batch.</b> Each slot reserves the next free send time,
 * {@code request-interval} after the previous one, and waits for it on a Reactor timer — never
 * sleeping the queue thread {@link #fetchAll} runs on. Sends are merged, so they stay evenly spaced
 * whatever the response latency. The clock persists across batches and cooldowns, so two sends are
 * never closer than {@code request-interval}, even across a batch boundary.
 *
 * <p><b>Cooldown is checked at send time.</b> A slot whose time comes up inside a cooldown is
 * reported failed without being sent — so a 510 or 403 mid-batch stops the rest of that batch, the
 * queue reopens, and those books ask again once {@link #isAcceptingRequests()} turns true. Cooldowns
 * only extend: a 3s throttle never cuts a 90s WAF pause short. No retries anywhere, as on Binance.
 */
@Slf4j
public class MexcSnapshotFetcher implements SnapshotFetcher {

    private static final int FORBIDDEN = 403;
    private static final int TOO_MANY_REQUESTS = 429;

    private enum Cooldown {
        THROTTLE, WAF
    }

    private final MexcDepthClient client;
    private final Venue venue;
    private final int depthLimit;
    private final long requestIntervalMs;
    private final long throttleCooldownMs;
    private final long wafCooldownMs;
    /** Times the sends, and is the fetcher's clock. */
    private final Scheduler scheduler;

    /**
     * When the next send may go out. Touched only by {@link #fetchAll}, on the queue's single thread,
     * and batches never overlap — so a plain field.
     */
    private long nextSendAtMs;

    /** Read by shard consumer threads; written under {@code this}. */
    private volatile long cooldownUntilMs;
    /** The cooldown in force, for logging only. Guarded by {@code this}. */
    private Cooldown cooldownKind;

    MexcSnapshotFetcher(MexcDepthClient client, MarketSnapshot props) {
        this(client, props, Schedulers.parallel());
    }

    MexcSnapshotFetcher(MexcDepthClient client, MarketSnapshot props, Scheduler scheduler) {
        this.client = client;
        this.venue = client.venue();
        this.depthLimit = props.depthLimit();
        this.requestIntervalMs = props.requestInterval().toMillis();
        // Absent only on spot, whose client never reports THROTTLED.
        this.throttleCooldownMs = props.throttleCooldown() == null ? 0 : props.throttleCooldown().toMillis();
        this.wafCooldownMs = props.wafCooldown().toMillis();
        this.scheduler = scheduler;
    }

    /** One volatile read: {@code false} while a cooldown is in force. */
    @Override
    public boolean isAcceptingRequests() {
        return now() >= cooldownUntilMs;
    }

    @Override
    public CompletionStage<Void> fetchAll(List<BookSlot> batch, SnapshotOutcome outcome) {
        long now = now();
        long firstDelayMs = Math.max(0, nextSendAtMs - now);
        List<Mono<Void>> sends = new ArrayList<>(batch.size());
        for (BookSlot slot : batch) {
            long delayMs = Math.max(0, nextSendAtMs - now);
            nextSendAtMs = now + delayMs + requestIntervalMs;
            sends.add(fetchOne(slot, delayMs, outcome));
        }
        log.debug("[{}] snapshot batch of {}: sends from +{} ms to +{} ms", venue, batch.size(),
                firstDelayMs, nextSendAtMs - requestIntervalMs - now);
        return Flux.merge(sends).then().toFuture();
    }

    private Mono<Void> fetchOne(BookSlot slot, long delayMs, SnapshotOutcome outcome) {
        return Mono.delay(Duration.ofMillis(delayMs), scheduler)
                .flatMap(tick -> {
                    if (!isAcceptingRequests()) {
                        log.debug("[{}] snapshot not sent - cooling down for another {} ms",
                                slot.instrument().logName(), cooldownUntilMs - now());
                        outcome.failed(slot);
                        return Mono.<String>empty();
                    }
                    return client.depth(slot.instrument().nativeSymbol(), depthLimit)
                            .defaultIfEmpty("")
                            .doOnNext(body -> onBody(slot, body, outcome));
                })
                .then()
                .onErrorResume(e -> {
                    onError(slot, e);
                    outcome.failed(slot);
                    return Mono.empty();
                });
    }

    private void onBody(BookSlot slot, String body, SnapshotOutcome outcome) {
        switch (client.classifyDepth(body)) {
            case OK -> outcome.delivered(slot, body);
            case THROTTLED -> {
                outcome.failed(slot);
                coolDown(Cooldown.THROTTLE, "MEXC throttled a snapshot request");
            }
            case REJECTED -> {
                outcome.failed(slot);
                log.warn("[{}] snapshot rejected: {}", slot.instrument().logName(), body);
            }
            case MALFORMED -> {
                outcome.failed(slot);
                log.warn("[{}] unreadable snapshot body ({} chars): {}", slot.instrument().logName(), body.length(),
                        body.length() > 200 ? body.substring(0, 200) + "…" : body);
            }
        }
    }

    /** The error itself is already logged at WARN by the REST client. */
    private void onError(BookSlot slot, Throwable e) {
        if (!(e instanceof ExchangeApiException api)) {
            // Timeout, connection reset, codec overflow…
            log.debug("[{}] snapshot failed without an HTTP status: {}", slot.instrument().logName(), e.toString());
            return;
        }
        int status = api.getStatusCode().value();
        if (status == FORBIDDEN || status == TOO_MANY_REQUESTS) {
            coolDown(Cooldown.WAF, "MEXC answered HTTP " + status + (status == FORBIDDEN ? " (Akamai WAF block)" : ""));
        }
    }

    /**
     * Extends the cooldown, never shortens it. Logged at WARN when it starts one, or escalates a
     * throttle pause to a WAF pause; every repeat within the pause at debug.
     */
    private void coolDown(Cooldown kind, String cause) {
        long now = now();
        long durationMs = kind == Cooldown.THROTTLE ? throttleCooldownMs : wafCooldownMs;
        long untilMs = now + durationMs;
        boolean escalated;
        long effectiveUntilMs;
        synchronized (this) {
            boolean inForce = now < cooldownUntilMs;
            escalated = !inForce || (kind == Cooldown.WAF && cooldownKind == Cooldown.THROTTLE);
            if (untilMs > cooldownUntilMs) {
                cooldownUntilMs = untilMs;
                cooldownKind = kind;
            }
            effectiveUntilMs = cooldownUntilMs;
        }
        if (escalated) {
            log.warn("[{}] {} - snapshot requests paused for {} ms, until {}",
                    venue, cause, durationMs, Instant.ofEpochMilli(effectiveUntilMs));
        } else {
            log.debug("[{}] {} - already paused until {}", venue, cause, Instant.ofEpochMilli(effectiveUntilMs));
        }
    }

    private long now() {
        return scheduler.now(TimeUnit.MILLISECONDS);
    }
}
