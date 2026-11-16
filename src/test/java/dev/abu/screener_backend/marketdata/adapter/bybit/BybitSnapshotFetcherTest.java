package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.bybit.BybitSnapshotFetcher.BodyKind;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.spi.SnapshotOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BybitSnapshotFetcher} over a real {@link BybitRestClient}, with responses scripted below
 * the {@link WebClient} and time driven by a {@link ManualScheduler}: nothing is sent until the test
 * advances the clock, and each send is stamped with the virtual time it went out at. Both venues'
 * fetchers share one {@link BybitRestCooldown}, as {@code BybitAdapterConfig} builds them.
 */
class BybitSnapshotFetcherTest {

    private static final long START = 1_000_000;
    private static final long INTERVAL = 100;
    private static final long RATE_LIMITED_PAUSE = 10_000;
    private static final long BANNED_PAUSE = 600_000;
    private static final String OK_BODY = "{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{\"s\":\"BTCUSDT\","
            + "\"a\":[[\"100.5\",\"3\"]],\"b\":[[\"100\",\"2\"]],\"ts\":1,\"u\":42,\"seq\":7,\"cts\":1},"
            + "\"retExtInfo\":{},\"time\":1}";

    private final ManualScheduler scheduler = new ManualScheduler(START);
    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    /** Per-symbol response; absent symbols get a 200 with {@link #OK_BODY}. */
    private final Map<String, Supplier<Mono<ClientResponse>>> script = new ConcurrentHashMap<>();
    private final RecordingOutcome outcome = new RecordingOutcome();
    private final BybitRestCooldown cooldown =
            new BybitRestCooldown(Duration.ofMillis(RATE_LIMITED_PAUSE), Duration.ofMillis(BANNED_PAUSE));

    private final BybitSnapshotFetcher fetcher = fetcher(Venue.BYBIT_FUTURES);
    private final BybitSnapshotFetcher spotFetcher = fetcher(Venue.BYBIT_SPOT);

    private record Sent(String symbol, long atMs, URI url) {}

    private BybitSnapshotFetcher fetcher(Venue venue) {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.bybit.com")
                .exchangeFunction(request -> {
                    String symbol = query(request.url(), "symbol");
                    sent.add(new Sent(symbol, scheduler.nowMs(), request.url()));
                    return script.getOrDefault(symbol, () -> Mono.just(json(HttpStatus.OK, OK_BODY))).get();
                })
                .build();
        return new BybitSnapshotFetcher(new BybitRestClient(venue, webClient), Duration.ofMillis(INTERVAL),
                cooldown, scheduler);
    }

    private static String query(URI url, String name) {
        return UriComponentsBuilder.fromUri(url).build().getQueryParams().getFirst(name);
    }

    // --- Responses -----------------------------------------------------------------------------

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static String retCode(int code, String msg) {
        return "{\"retCode\":" + code + ",\"retMsg\":\"" + msg + "\",\"result\":{},\"retExtInfo\":{},\"time\":1}";
    }

    private void respond(String symbol, ClientResponse response) {
        script.put(symbol, () -> Mono.just(response));
    }

    // --- Fixtures ------------------------------------------------------------------------------

    private static List<InstrumentSlot> batch(Venue venue, String... symbols) {
        List<InstrumentSlot> slots = new ArrayList<>();
        for (int i = 0; i < symbols.length; i++) {
            slots.add(new InstrumentSlot(Instrument.of(i, venue, symbols[i], symbols[i].replace("USDT", ""), "USDT"),
                    new OrderBook(0.1), null, null));
        }
        return slots;
    }

    private static List<InstrumentSlot> batch(String... symbols) {
        return batch(Venue.BYBIT_FUTURES, symbols);
    }

    /** Starts a batch and runs virtual time until every send window of it has passed. */
    private CompletableFuture<Void> fetch(BybitSnapshotFetcher fetcher, List<InstrumentSlot> batch) {
        CompletableFuture<Void> stage = fetcher.fetchAll(batch, outcome).toCompletableFuture();
        scheduler.advanceBy(batch.size() * INTERVAL);
        assertTrue(stage.isDone(), "every slot has an outcome once its send window has passed");
        return stage;
    }

    private CompletableFuture<Void> fetch(List<InstrumentSlot> batch) {
        return fetch(fetcher, batch);
    }

    private List<String> sentSymbols() {
        return sent.stream().map(Sent::symbol).toList();
    }

    private List<Long> sendOffsets() {
        return sent.stream().map(s -> s.atMs() - START).toList();
    }

    /** Asserts both venues stop accepting until exactly {@code untilMs}. */
    private void assertPausedUntil(long untilMs) {
        scheduler.advanceBy(untilMs - 1 - scheduler.nowMs());
        assertFalse(fetcher.isAcceptingRequests());
        assertFalse(spotFetcher.isAcceptingRequests(), "the limit is per IP: both venues pause");
        scheduler.advanceBy(1);
        assertTrue(fetcher.isAcceptingRequests());
        assertTrue(spotFetcher.isAcceptingRequests());
    }

    private static final class RecordingOutcome implements SnapshotOutcome {
        final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        final List<String> failed = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void delivered(InstrumentSlot slot, String body) {
            delivered.add(slot.instrument().nativeSymbol());
            bodies.add(body);
        }

        @Override
        public void failed(InstrumentSlot slot) {
            failed.add(slot.instrument().nativeSymbol());
        }
    }

    // --- Delivery ------------------------------------------------------------------------------

    @Test
    @DisplayName("a retCode 0 body is delivered intact, from /v5/market/orderbook?category=linear&limit=1000")
    void successDelivered() {
        fetch(batch("BTCUSDT"));

        assertEquals(List.of("BTCUSDT"), outcome.delivered);
        assertEquals(List.of(OK_BODY), outcome.bodies, "the strategy parses the envelope-wrapped body itself");
        URI url = sent.getFirst().url();
        assertEquals("/v5/market/orderbook", url.getPath());
        assertEquals("linear", query(url, "category"));
        assertEquals("1000", query(url, "limit"), "the subscribed depth: REST u stitches onto orderbook.1000");
    }

    @Test
    @DisplayName("spot asks for category=spot")
    void spotCategory() {
        fetch(spotFetcher, batch(Venue.BYBIT_SPOT, "BTCUSDT"));

        assertEquals(List.of("BTCUSDT"), outcome.delivered);
        assertEquals("spot", query(sent.getFirst().url(), "category"));
    }

    @Test
    @DisplayName("fetchAll starts the timers and returns; nothing is sent until time runs")
    void fetchAllDoesNotBlock() {
        CompletableFuture<Void> stage = fetcher.fetchAll(batch("AUSDT", "BUSDT"), outcome).toCompletableFuture();

        assertTrue(sent.isEmpty());
        assertFalse(stage.isDone());

        scheduler.advanceBy(2 * INTERVAL);
        assertTrue(stage.isDone());
        assertEquals(2, outcome.delivered.size());
    }

    // --- Pacing --------------------------------------------------------------------------------

    @Test
    @DisplayName("sends within a batch are spaced request-interval apart")
    void spacedWithinBatch() {
        fetch(batch("AUSDT", "BUSDT", "CUSDT", "DUSDT"));

        assertEquals(List.of("AUSDT", "BUSDT", "CUSDT", "DUSDT"), sentSymbols());
        assertEquals(List.of(0L, 100L, 200L, 300L), sendOffsets());
    }

    @Test
    @DisplayName("the send clock persists across batches")
    void spacingPersistsAcrossBatches() {
        CompletableFuture<Void> first = fetcher.fetchAll(batch("AUSDT", "BUSDT"), outcome).toCompletableFuture();
        scheduler.advanceBy(INTERVAL);
        assertTrue(first.isDone());

        fetch(batch("CUSDT", "DUSDT"));

        assertEquals(List.of(0L, 100L, 200L, 300L), sendOffsets());
    }

    // --- Rate limit (HTTP 200, retCode 10006 / 10018) ------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {10006, 10018})
    @DisplayName("a rate-limit retCode in an HTTP 200 is never delivered; the rest of the batch fails unsent; both venues pause")
    void rateLimitedMidBatch(int code) {
        String body = retCode(code, "Too many visits!");
        respond("BUSDT", json(HttpStatus.OK, body));

        fetch(batch("AUSDT", "BUSDT", "CUSDT", "DUSDT"));

        assertEquals(List.of("AUSDT", "BUSDT"), sentSymbols(), "C and D come up inside the pause");
        assertEquals(List.of("AUSDT"), outcome.delivered);
        assertEquals(List.of("BUSDT", "CUSDT", "DUSDT"), outcome.failed);
        assertFalse(outcome.bodies.contains(body), "a rate-limit body must never reach the strategy");
        assertPausedUntil(START + INTERVAL + RATE_LIMITED_PAUSE);
    }

    @Test
    @DisplayName("an HTTP 429 pauses both venues for the rate-limit pause")
    void http429() {
        respond("AUSDT", json(HttpStatus.TOO_MANY_REQUESTS, "{}"));

        fetch(batch("AUSDT", "BUSDT"));

        assertEquals(List.of("AUSDT", "BUSDT"), outcome.failed);
        assertPausedUntil(START + RATE_LIMITED_PAUSE);
    }

    // --- Ban (HTTP 403) ------------------------------------------------------------------------

    @Test
    @DisplayName("an HTTP 403 (the 10-minute IP ban) pauses both venues for the ban pause")
    void bannedPausesBothVenues() {
        respond("AUSDT", json(HttpStatus.FORBIDDEN, "access too frequent"));

        fetch(batch("AUSDT", "BUSDT"));

        assertEquals(List.of("AUSDT"), sentSymbols());
        assertEquals(List.of("AUSDT", "BUSDT"), outcome.failed);

        // The other venue's next batch fails without a single send.
        CompletableFuture<Void> spot = spotFetcher.fetchAll(batch(Venue.BYBIT_SPOT, "ETHUSDT"), outcome).toCompletableFuture();
        scheduler.advanceBy(INTERVAL);
        assertTrue(spot.isDone());
        assertEquals(List.of("AUSDT"), sentSymbols());
        assertEquals(List.of("AUSDT", "BUSDT", "ETHUSDT"), outcome.failed);

        assertPausedUntil(START + BANNED_PAUSE);
    }

    @Test
    @DisplayName("a pause only extends: a rate limit landing during a ban does not shorten it")
    void pauseNeverShortens() {
        // A is still in flight when B is sent and banned; A's rate limit then lands mid-ban.
        script.put("AUSDT", () -> Mono.delay(Duration.ofMillis(300), scheduler)
                .map(x -> json(HttpStatus.OK, retCode(10006, "Too many visits!"))));
        respond("BUSDT", json(HttpStatus.FORBIDDEN, ""));

        fetcher.fetchAll(batch("AUSDT", "BUSDT"), outcome);
        scheduler.advanceBy(300);

        assertEquals(List.of("AUSDT", "BUSDT"), outcome.failed.stream().sorted().toList());
        assertPausedUntil(START + INTERVAL + BANNED_PAUSE);
    }

    // --- Other failures: reported failed, no pause ---------------------------------------------

    @Test
    @DisplayName("any other retCode fails its slot only, with no pause")
    void otherRetCodeNoPause() {
        respond("AUSDT", json(HttpStatus.OK, retCode(10001, "params error: symbol invalid")));

        fetch(batch("AUSDT", "BUSDT"));

        assertEquals(List.of("AUSDT"), outcome.failed);
        assertEquals(List.of("BUSDT"), outcome.delivered);
        assertTrue(fetcher.isAcceptingRequests());
    }

    @Test
    @DisplayName("another error status or a connection error fails its slot only, with no pause")
    void otherErrorsNoPause() {
        respond("AUSDT", json(HttpStatus.INTERNAL_SERVER_ERROR, "{}"));
        script.put("BUSDT", () -> Mono.error(new IOException("Connection reset")));

        fetch(batch("AUSDT", "BUSDT", "CUSDT"));

        assertEquals(List.of("AUSDT", "BUSDT"), outcome.failed);
        assertEquals(List.of("CUSDT"), outcome.delivered);
        assertTrue(fetcher.isAcceptingRequests());
    }

    @Test
    @DisplayName("an unreadable or empty 200 body is a failure, never a delivery")
    void unreadableBodyFailed() {
        respond("AUSDT", ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                .body("<html>maintenance</html>").build());
        respond("BUSDT", ClientResponse.create(HttpStatus.OK).build());

        fetch(batch("AUSDT", "BUSDT"));

        assertEquals(List.of("AUSDT", "BUSDT"), outcome.failed);
        assertTrue(outcome.delivered.isEmpty());
        assertTrue(fetcher.isAcceptingRequests());
    }

    // --- Classification ------------------------------------------------------------------------

    @Test
    @DisplayName("classify reads retCode only: the captured REST snapshot is OK, whatever its spacing")
    void classify() {
        assertEquals(BodyKind.OK, BybitSnapshotFetcher.classify(
                BybitRestClientTest.fixture("rest-orderbook-linear-ARBUSDT-1000.json")));
        assertEquals(BodyKind.RATE_LIMITED, BybitSnapshotFetcher.classify(retCode(10006, "x")));
        assertEquals(BodyKind.RATE_LIMITED, BybitSnapshotFetcher.classify(retCode(10018, "x")));
        assertEquals(BodyKind.REJECTED, BybitSnapshotFetcher.classify(retCode(10001, "x")));
        assertEquals(BodyKind.MALFORMED, BybitSnapshotFetcher.classify(""));
        assertEquals(BodyKind.MALFORMED, BybitSnapshotFetcher.classify("<html></html>"));
        assertEquals(BodyKind.MALFORMED, BybitSnapshotFetcher.classify("{\"retCode\":\"0\"}"));
        assertEquals(BodyKind.MALFORMED, BybitSnapshotFetcher.classify("{\"result\":{}}"));
    }

    @Test
    @DisplayName("the cooldown rejects non-positive pauses")
    void cooldownValidates() {
        assertThrows(IllegalArgumentException.class, () -> new BybitRestCooldown(Duration.ZERO, Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class, () -> new BybitRestCooldown(Duration.ofSeconds(10), null));
    }
}
