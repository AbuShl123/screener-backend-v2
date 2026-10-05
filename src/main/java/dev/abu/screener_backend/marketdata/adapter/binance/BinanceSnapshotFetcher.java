package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceSnapshotProperties.MarketSnapshot;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeApiException;
import dev.abu.screener_backend.marketdata.spi.SnapshotFetcher;
import dev.abu.screener_backend.marketdata.spi.SnapshotOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/**
 * Fetches depth snapshots for one Binance venue, and is the only component that tracks and enforces
 * that venue's request-weight limit — read from the headers of its own depth responses.
 *
 * <p><b>Accounting is exact.</b> The queue never overlaps batches, so when {@link #fetchAll} starts
 * nothing from this fetcher is in flight and the last observed header is the true usage, apart from
 * the rare discovery call the reserve covers. A batch is clamped to what that usage still affords;
 * the rest is reported failed at once and re-asks once the minute rolls.
 *
 * <p><b>All requests in a batch go out in parallel.</b> That suits a minute-window limit: the budget
 * fills quickly, {@link #isAcceptingRequests()} turns false, and books wait in {@code PENDING}
 * without buffering until the window rolls.
 *
 * <p>No retries anywhere: a failed request is reported failed and the book asks again on its own.
 */
@Slf4j
public class BinanceSnapshotFetcher implements SnapshotFetcher {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final int IP_BANNED = 418;

    private final Venue venue;
    private final BinanceRestClient client;
    private final WeightGuard guard;
    private final int depthLimit;
    private final int weightPerRequest;
    private final long weightBudget;
    private final Duration banFallback;
    private final LongSupplier clock;

    public BinanceSnapshotFetcher(BinanceRestClient client, MarketSnapshot props) {
        this(client, props, new WeightGuard(), System::currentTimeMillis);
    }

    BinanceSnapshotFetcher(BinanceRestClient client, MarketSnapshot props, WeightGuard guard, LongSupplier clock) {
        this.venue = client.venue();
        this.client = client;
        this.guard = guard;
        this.depthLimit = props.depthLimit();
        this.weightPerRequest = props.weightPerRequest(venue.market());
        this.weightBudget = props.weightBudget();
        this.banFallback = props.banFallback();
        this.clock = clock;
    }

    @Override
    public boolean isAcceptingRequests() {
        long now = clock.getAsLong();
        return !guard.isBanned(now) && guard.remaining(weightBudget, now) >= weightPerRequest;
    }

    @Override
    public CompletionStage<Void> fetchAll(List<BookSlot> batch, SnapshotOutcome outcome) {
        long now = clock.getAsLong();
        boolean banned = guard.isBanned(now);
        long remaining = guard.remaining(weightBudget, now);
        long affordable = banned ? 0 : Math.max(0, remaining / weightPerRequest);
        int sendCount = (int) Math.min(affordable, batch.size());

        for (int i = sendCount; i < batch.size(); i++) {
            outcome.failed(batch.get(i));   // skipped for budget
        }

        if (banned) {
            log.debug("[{}] snapshot batch of {}: all skipped - banned for another {} ms",
                    venue, batch.size(), guard.bannedUntilMs() - now);
        }

        return Flux.fromIterable(batch.subList(0, sendCount))
                .flatMap(slot -> fetchOne(slot, outcome))
                .then()
                .toFuture();
    }

    private Mono<Void> fetchOne(BookSlot slot, SnapshotOutcome outcome) {
        return client.depth(slot.instrument().nativeSymbol(), depthLimit)
                .doOnNext(response -> onResponse(slot, response, outcome))
                .then()
                .onErrorResume(e -> {
                    onError(slot, e);
                    outcome.failed(slot);
                    return Mono.empty();
                });
    }

    private void onResponse(BookSlot slot, ResponseEntity<String> response, SnapshotOutcome outcome) {
        guard.observe(response.getHeaders(), clock.getAsLong());
        String body = response.getBody();
        if (body == null || body.isEmpty()) {
            log.warn("[{}] empty snapshot body", slot.instrument().logName());
            outcome.failed(slot);
        } else {
            outcome.delivered(slot, body);
        }
    }

    /**
     * Learns from an error response; the error itself is already logged at WARN by
     * {@link BinanceRestClient}. A 429 or 418 bans the guard for {@code Retry-After} seconds — applied
     * to the local clock, since it is relative — or, without the header, until the next server minute
     * (429) or for {@code ban-fallback} (418).
     */
    private void onError(BookSlot slot, Throwable e) {
        if (!(e instanceof ExchangeApiException api)) {
            // Timeout, connection reset, codec overflow… — no headers to learn from.
            log.debug("[{}] snapshot failed without an HTTP status: {}", slot.instrument().logName(), e.toString());
            return;
        }
        HttpHeaders headers = api.getHeaders();
        long now = clock.getAsLong();
        guard.observe(headers, now);

        int status = api.getStatusCode().value();
        log.debug("[{}] snapshot failed - HTTP {}, used weight {}, Retry-After {}, server time {}, body: {}",
                slot.instrument().logName(), status, headers.getFirst(WeightGuard.WEIGHT_HEADER),
                headers.getFirst(HttpHeaders.RETRY_AFTER), headers.getFirst(HttpHeaders.DATE), api.getResponseBody());
        if (status != TOO_MANY_REQUESTS && status != IP_BANNED) return;

        long banMs = retryAfterMs(headers);
        String banSource = "Retry-After";
        if (banMs < 0) {
            long serverNow = WeightGuard.serverTimeMs(headers, now);
            banMs = status == TOO_MANY_REQUESTS
                    ? WeightGuard.nextMinuteBoundary(serverNow) - serverNow
                    : banFallback.toMillis();
            banSource = status == TOO_MANY_REQUESTS ? "next server minute" : "ban-fallback";
        }
        boolean wasBanned = guard.isBanned(now);
        long previousUntil = guard.bannedUntilMs();
        guard.ban(now + banMs);
        log.debug("[{}] ban from HTTP {}: {} ms ({}) - suspended until {} (was {})", venue, status, banMs, banSource,
                Instant.ofEpochMilli(guard.bannedUntilMs()), wasBanned ? Instant.ofEpochMilli(previousUntil) : "not banned");
        if (!wasBanned) {
            log.error("[{}] Binance answered {} - snapshot requests suspended for {} ms, until {}",
                    venue, status, banMs, Instant.ofEpochMilli(now + banMs));
        }
    }

    /** {@code Retry-After} in milliseconds, or {@code -1} if absent or not a number of seconds. */
    private static long retryAfterMs(HttpHeaders headers) {
        String raw = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (raw == null) return -1;
        try {
            return Math.max(0, Long.parseLong(raw.trim())) * 1000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
