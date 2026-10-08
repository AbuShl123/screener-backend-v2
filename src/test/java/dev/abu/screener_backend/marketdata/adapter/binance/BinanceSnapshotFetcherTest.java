package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.binance.BinancePaths;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceRestClient;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceSnapshotFetcher;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceSnapshotProperties.MarketSnapshot;
import dev.abu.screener_backend.marketdata.adapter.binance.WeightGuard;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.spi.SnapshotOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BinanceSnapshotFetcher} over a real {@link BinanceRestClient}, with responses scripted
 * below the {@link WebClient}. The clock is pinned inside one server minute so window arithmetic
 * never straddles a boundary.
 */
class BinanceSnapshotFetcherTest {

    /** Server time on every scripted response: 12:00:10. */
    private static final long SERVER_NOW = Instant.parse("2026-10-02T12:00:10Z").toEpochMilli();
    private static final long BUDGET = 5800;   // 6000 - 200
    private static final int SPOT_WEIGHT = 50; // limit=1000 on spot
    private static final Duration BAN_FALLBACK = Duration.ofMinutes(2);

    private final AtomicLong clock = new AtomicLong(SERVER_NOW + 1_000);
    private final WeightGuard guard = new WeightGuard();
    private final List<String> requested = Collections.synchronizedList(new ArrayList<>());
    /** Per-symbol response; absent symbols get a 200 with a body and weight 100. */
    private final Map<String, Function<String, Mono<ClientResponse>>> script = new ConcurrentHashMap<>();
    private final RecordingOutcome outcome = new RecordingOutcome();

    private final BinanceSnapshotFetcher fetcher = new BinanceSnapshotFetcher(client(),
            new MarketSnapshot(1000, 6000, 200, BAN_FALLBACK), guard, clock::get);

    private BinanceRestClient client() {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.binance.com")
                .exchangeFunction(request -> {
                    String symbol = UriComponentsBuilder.fromUri(request.url()).build().getQueryParams().getFirst("symbol");
                    requested.add(symbol);
                    return script.getOrDefault(symbol, s -> Mono.just(ok(100))).apply(symbol);
                })
                .build();
        return new BinanceRestClient(Venue.BINANCE_SPOT, webClient, BinancePaths.SPOT);
    }

    // --- Responses -----------------------------------------------------------------------------

    private static String date(long ms) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC));
    }

    private static ClientResponse ok(long weight) {
        return ClientResponse.create(HttpStatus.OK)
                .header(WeightGuard.WEIGHT_HEADER, String.valueOf(weight))
                .header(HttpHeaders.DATE, date(SERVER_NOW))
                .body("{\"lastUpdateId\":1,\"bids\":[],\"asks\":[]}")
                .build();
    }

    private static ClientResponse error(HttpStatus status, String... headerPairs) {
        ClientResponse.Builder b = ClientResponse.create(status).header(HttpHeaders.DATE, date(SERVER_NOW));
        for (int i = 0; i < headerPairs.length; i += 2) b.header(headerPairs[i], headerPairs[i + 1]);
        return b.body("{\"code\":-1003,\"msg\":\"Too many requests\"}").build();
    }

    private void respond(String symbol, ClientResponse response) {
        script.put(symbol, s -> Mono.just(response));
    }

    // --- Fixtures ------------------------------------------------------------------------------

    private static List<BookSlot> batch(String... symbols) {
        List<BookSlot> slots = new ArrayList<>();
        for (int i = 0; i < symbols.length; i++) {
            String base = symbols[i].replace("USDT", "");
            slots.add(new BookSlot(Instrument.of(i, Venue.BINANCE_SPOT, symbols[i], base, "USDT"),
                    new OrderBook(0.1, 2.0), null, null));
        }
        return slots;
    }

    private void fetch(List<BookSlot> batch) {
        fetcher.fetchAll(batch, outcome).toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static final class RecordingOutcome implements SnapshotOutcome {
        final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        final List<String> failed = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void delivered(BookSlot slot, String body) {
            delivered.add(slot.instrument().nativeSymbol());
        }

        @Override
        public void failed(BookSlot slot) {
            failed.add(slot.instrument().nativeSymbol());
        }

        int total() {
            return delivered.size() + failed.size();
        }
    }

    // --- Tests ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a batch is clamped to what the budget affords; the skipped slots are failed at once")
    void budgetClamp() {
        guard.observe(SERVER_NOW, SERVER_NOW, BUDGET - 2 * SPOT_WEIGHT);   // room for exactly two requests

        fetch(batch("AUSDT", "BUSDT", "CUSDT", "DUSDT", "EUSDT"));

        assertEquals(List.of("AUSDT", "BUSDT"), requested, "only the affordable prefix goes out");
        assertEquals(List.of("AUSDT", "BUSDT"), outcome.delivered);
        assertEquals(List.of("CUSDT", "DUSDT", "EUSDT"), outcome.failed);
    }

    @Test
    @DisplayName("an error response is reported failed; the rest of the batch still delivers")
    void errorIsFailed() {
        respond("BUSDT", error(HttpStatus.INTERNAL_SERVER_ERROR));

        fetch(batch("AUSDT", "BUSDT", "CUSDT"));

        assertEquals(List.of("AUSDT", "CUSDT"), outcome.delivered.stream().sorted().toList());
        assertEquals(List.of("BUSDT"), outcome.failed);
    }

    @Test
    @DisplayName("an empty body is a failure, never a delivery")
    void emptyBodyIsFailed() {
        respond("AUSDT", ClientResponse.create(HttpStatus.OK).header(HttpHeaders.DATE, date(SERVER_NOW)).build());

        fetch(batch("AUSDT"));

        assertEquals(List.of("AUSDT"), outcome.failed);
        assertTrue(outcome.delivered.isEmpty());
    }

    @Test
    @DisplayName("the stage completes only once every slot has an outcome")
    void stageCompletesAfterAllOutcomes() {
        script.put("AUSDT", s -> Mono.delay(Duration.ofMillis(100)).map(x -> ok(100)));
        script.put("BUSDT", s -> Mono.delay(Duration.ofMillis(150)).map(x -> error(HttpStatus.BAD_GATEWAY)));

        CompletableFuture<Void> stage = fetcher.fetchAll(batch("AUSDT", "BUSDT", "CUSDT"), outcome).toCompletableFuture();
        assertFalse(stage.isDone(), "fetchAll starts the requests and returns");

        stage.orTimeout(5, TimeUnit.SECONDS).join();
        assertEquals(3, outcome.total());
    }

    @Test
    @DisplayName("weight is observed from successful responses")
    void weightFromSuccess() {
        respond("AUSDT", ok(1234));

        fetch(batch("AUSDT"));

        assertEquals(BUDGET - 1234, guard.remaining(BUDGET, clock.get()));
    }

    @Test
    @DisplayName("weight is observed from error responses too")
    void weightFromError() {
        respond("AUSDT", error(HttpStatus.BAD_REQUEST, WeightGuard.WEIGHT_HEADER, "2000"));

        fetch(batch("AUSDT"));

        assertEquals(BUDGET - 2000, guard.remaining(BUDGET, clock.get()));
    }

    @Test
    @DisplayName("429 with Retry-After bans for exactly that long, and the fetcher stops accepting")
    void tooManyRequestsBansFromRetryAfter() {
        respond("AUSDT", error(HttpStatus.TOO_MANY_REQUESTS, HttpHeaders.RETRY_AFTER, "8"));

        fetch(batch("AUSDT"));

        long now = clock.get();
        assertEquals(List.of("AUSDT"), outcome.failed);
        assertFalse(fetcher.isAcceptingRequests());
        assertTrue(guard.isBanned(now + 7_999));
        assertFalse(guard.isBanned(now + 8_000));
    }

    @Test
    @DisplayName("429 without Retry-After bans until the next server minute")
    void tooManyRequestsFallsBackToMinuteBoundary() {
        respond("AUSDT", error(HttpStatus.TOO_MANY_REQUESTS));

        fetch(batch("AUSDT"));

        long now = clock.get();   // server is at 12:00:10 → 50s left in its minute
        assertTrue(guard.isBanned(now + 49_999));
        assertFalse(guard.isBanned(now + 50_000));
    }

    @Test
    @DisplayName("418 with Retry-After bans for that long")
    void ipBanFromRetryAfter() {
        respond("AUSDT", error(HttpStatus.I_AM_A_TEAPOT, HttpHeaders.RETRY_AFTER, "300"));

        fetch(batch("AUSDT"));

        long now = clock.get();
        assertTrue(guard.isBanned(now + 299_999));
        assertFalse(guard.isBanned(now + 300_000));
    }

    @Test
    @DisplayName("418 without Retry-After bans for ban-fallback")
    void ipBanFallsBackToConfig() {
        respond("AUSDT", error(HttpStatus.I_AM_A_TEAPOT));

        fetch(batch("AUSDT"));

        long now = clock.get();
        assertTrue(guard.isBanned(now + BAN_FALLBACK.toMillis() - 1));
        assertFalse(guard.isBanned(now + BAN_FALLBACK.toMillis()));
    }

    @Test
    @DisplayName("while banned, nothing is sent and the whole batch is failed")
    void bannedSendsNothing() {
        guard.ban(clock.get() + 10_000);

        assertFalse(fetcher.isAcceptingRequests());
        fetch(batch("AUSDT", "BUSDT"));

        assertTrue(requested.isEmpty());
        assertEquals(List.of("AUSDT", "BUSDT"), outcome.failed);
    }

    @Test
    @DisplayName("out of budget: not accepting until the server minute rolls")
    void outOfBudgetUntilWindowRolls() {
        assertTrue(fetcher.isAcceptingRequests(), "nothing observed yet");

        guard.observe(SERVER_NOW, SERVER_NOW, BUDGET - SPOT_WEIGHT + 1);   // one short of a request
        assertFalse(fetcher.isAcceptingRequests());

        clock.set(Instant.parse("2026-10-02T12:01:00Z").toEpochMilli());
        assertTrue(fetcher.isAcceptingRequests());
    }
}
