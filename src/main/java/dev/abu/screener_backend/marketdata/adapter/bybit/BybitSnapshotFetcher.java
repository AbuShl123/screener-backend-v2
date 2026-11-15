package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.bybit.BybitRestCooldown.Kind;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeApiException;
import dev.abu.screener_backend.marketdata.spi.SnapshotFetcher;
import dev.abu.screener_backend.marketdata.spi.SnapshotOutcome;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Fetches {@code GET /v5/market/orderbook?limit=1000} snapshots for one Bybit venue: classifies each
 * response by its {@code retCode} before reporting it, and paces every send at the venue's
 * {@code request-interval}. One instance per venue, each with its own send clock, sharing one
 * {@link BybitRestCooldown}, because Bybit's limit and ban are per IP across both venues
 * ({@code external-docs/bybit/bybit-api-contracts.md} §3.6).
 *
 * <p>Gaps are rare on Bybit (cold start and reconnects sync from the in-stream snapshot), so this is
 * a safety net, paced well under the limit rather than tuned for throughput.
 *
 * <p><b>Classify by {@code retCode}, not HTTP status.</b> Bybit rate-limits with an HTTP 200
 * (contracts §3.1), so a 2xx is not a snapshot until its envelope says {@code retCode 0}.
 * Classification reads the envelope only, never the levels.
 *
 * <table>
 *   <tr><th>Response</th><th>Report</th><th>Pause (both venues)</th></tr>
 *   <tr><td>2xx, {@code retCode 0}</td><td>delivered</td><td>—</td></tr>
 *   <tr><td>2xx, {@code retCode 10006} / {@code 10018}, or HTTP 429</td><td>failed</td><td>{@code rate-limited}</td></tr>
 *   <tr><td>HTTP 403 (IP ban or geo-block)</td><td>failed</td><td>{@code banned}</td></tr>
 *   <tr><td>2xx, any other {@code retCode} (WARN), unreadable body (WARN), other status, timeout</td><td>failed</td><td>—</td></tr>
 * </table>
 *
 * <p>The pacing and cooldown mechanics are {@code MexcSnapshotFetcher}'s: one send clock that
 * persists across batches, each send waiting on a Reactor timer (never sleeping the queue thread
 * {@link #fetchAll} runs on), and the cooldown checked at send time, so a pushback mid-batch fails
 * the rest of the batch unsent. No retries.
 */
@Slf4j
public class BybitSnapshotFetcher implements SnapshotFetcher {

    /** The subscribed depth: REST {@code u} only stitches onto the {@code orderbook.1000} counter. */
    static final int DEPTH_LIMIT = BybitStreamProtocol.DEPTH;

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private static final int FORBIDDEN = 403;
    private static final int TOO_MANY_REQUESTS = 429;
    /** "Too many visits!" */
    private static final int RET_CODE_RATE_LIMIT = 10006;
    /** IP rate limit. */
    private static final int RET_CODE_IP_RATE_LIMIT = 10018;

    /** What a 2xx depth body says. */
    enum BodyKind {
        /** {@code retCode 0}: hand it to the sync strategy. */
        OK,
        /** {@code retCode} 10006 / 10018: start the rate-limit pause. */
        RATE_LIMITED,
        /** Any other non-zero {@code retCode}: an error for this request only. */
        REJECTED,
        /** No readable {@code retCode}: empty, HTML, or an unknown shape. */
        MALFORMED
    }

    private final BybitRestClient client;
    private final Venue venue;
    private final long requestIntervalMs;
    private final BybitRestCooldown cooldown;
    /** Times the sends, and is the fetcher's clock. */
    private final Scheduler scheduler;

    /**
     * When the next send may go out. Touched only by {@link #fetchAll}, on the queue's single thread,
     * and batches never overlap — so a plain field.
     */
    private long nextSendAtMs;

    public BybitSnapshotFetcher(BybitRestClient client, Duration requestInterval, BybitRestCooldown cooldown) {
        this(client, requestInterval, cooldown, Schedulers.parallel());
    }

    BybitSnapshotFetcher(BybitRestClient client, Duration requestInterval, BybitRestCooldown cooldown,
                         Scheduler scheduler) {
        this.client = client;
        this.venue = client.venue();
        this.requestIntervalMs = requestInterval.toMillis();
        this.cooldown = cooldown;
        this.scheduler = scheduler;
    }

    /** One volatile read: {@code false} while either venue's pushback pause is in force. */
    @Override
    public boolean isAcceptingRequests() {
        return !cooldown.isActive(now());
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
                        log.debug("[{}] snapshot not sent - paused for another {} ms",
                                slot.instrument().logName(), cooldown.remainingMs(now()));
                        outcome.failed(slot);
                        return Mono.<String>empty();
                    }
                    return client.depth(slot.instrument().nativeSymbol(), DEPTH_LIMIT)
                            .defaultIfEmpty("")
                            .doOnNext(body -> onBody(slot, body, outcome));
                })
                .then()
                .onErrorResume(e -> {
                    onError(e);
                    outcome.failed(slot);
                    return Mono.empty();
                });
    }

    private void onBody(BookSlot slot, String body, SnapshotOutcome outcome) {
        switch (classify(body)) {
            case OK -> outcome.delivered(slot, body);
            case RATE_LIMITED -> {
                outcome.failed(slot);
                cooldown.start(Kind.RATE_LIMITED, now(), "[" + venue + "] Bybit rate-limited a snapshot request: " + abbreviate(body));
            }
            case REJECTED -> {
                outcome.failed(slot);
                log.warn("[{}] snapshot rejected: {}", slot.instrument().logName(), abbreviate(body));
            }
            case MALFORMED -> {
                outcome.failed(slot);
                log.warn("[{}] unreadable snapshot body ({} chars): {}", slot.instrument().logName(), body.length(),
                        abbreviate(body));
            }
        }
    }

    /** The error itself is already logged at WARN by the REST client. */
    private void onError(Throwable e) {
        if (!(e instanceof ExchangeApiException api)) {
            // Timeout, connection reset, codec overflow…
            log.debug("[{}] snapshot failed without an HTTP status: {}", venue, e.toString());
            return;
        }
        int status = api.getStatusCode().value();
        if (status == FORBIDDEN) {
            cooldown.start(Kind.BANNED, now(), "[" + venue + "] Bybit answered HTTP 403 (IP ban or geo-block)");
        } else if (status == TOO_MANY_REQUESTS) {
            cooldown.start(Kind.RATE_LIMITED, now(), "[" + venue + "] Bybit answered HTTP 429");
        }
    }

    /**
     * Reads the envelope's {@code retCode}, which Bybit sends first, and nothing past it. Cold path:
     * one call per snapshot.
     */
    static BodyKind classify(String body) {
        if (body == null || body.isEmpty()) return BodyKind.MALFORMED;
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return BodyKind.MALFORMED;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if (field.equals("retCode")) {
                    if (value != JsonToken.VALUE_NUMBER_INT) return BodyKind.MALFORMED;
                    return switch (p.getIntValue()) {
                        case 0 -> BodyKind.OK;
                        case RET_CODE_RATE_LIMIT, RET_CODE_IP_RATE_LIMIT -> BodyKind.RATE_LIMITED;
                        default -> BodyKind.REJECTED;
                    };
                }
                p.skipChildren();
            }
            return BodyKind.MALFORMED;
        } catch (RuntimeException e) {
            return BodyKind.MALFORMED;
        }
    }

    private static String abbreviate(String body) {
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    private long now() {
        return scheduler.now(TimeUnit.MILLISECONDS);
    }
}
