package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcFuturesRestClient;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSnapshotFetcher;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSnapshotFetcher.BodyKind;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSnapshotProperties.MarketSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MexcSnapshotFetcher} over a real {@link MexcFuturesRestClient}, with responses scripted
 * below the {@link WebClient} and time driven by a {@link ManualScheduler}: nothing is sent until
 * the test advances the clock, and each send is stamped with the virtual time it went out at.
 */
class MexcSnapshotFetcherTest {

    private static final long START = 1_000_000;
    private static final long INTERVAL = 250;
    private static final long THROTTLE_COOLDOWN = 3_000;
    private static final long WAF_COOLDOWN = 90_000;
    private static final String OK_BODY =
            "{\"success\":true,\"code\":0,\"data\":{\"asks\":[[100.5,3,1]],\"bids\":[[100,2,1]],\"version\":42,\"timestamp\":1}}";
    private static final String THROTTLED_BODY =
            "{\"success\":false,\"code\":510,\"message\":\"Requests are too frequent, please try again later\"}";

    private final ManualScheduler scheduler = new ManualScheduler(START);
    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    /** Per-symbol response; absent symbols get a 200 with {@link #OK_BODY}. */
    private final Map<String, Supplier<Mono<ClientResponse>>> script = new ConcurrentHashMap<>();
    private final RecordingOutcome outcome = new RecordingOutcome();

    private final MexcSnapshotFetcher fetcher = new MexcSnapshotFetcher(client(),
            new MarketSnapshot(1500, Duration.ofMillis(INTERVAL), Duration.ofMillis(THROTTLE_COOLDOWN),
                    Duration.ofMillis(WAF_COOLDOWN)),
            scheduler);

    private record Sent(String symbol, long atMs, URI url) {}

    private MexcFuturesRestClient client() {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.mexc.com")
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    String symbol = path.substring(path.lastIndexOf('/') + 1);
                    sent.add(new Sent(symbol, scheduler.nowMs(), request.url()));
                    return script.getOrDefault(symbol, () -> Mono.just(json(HttpStatus.OK, OK_BODY))).get();
                })
                .build();
        return new MexcFuturesRestClient(webClient);
    }

    // --- Responses -----------------------------------------------------------------------------

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static ClientResponse html(HttpStatus status) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                .body("<HTML><HEAD><TITLE>Access Denied</TITLE></HEAD></HTML>")
                .build();
    }

    private void respond(String symbol, ClientResponse response) {
        script.put(symbol, () -> Mono.just(response));
    }

    // --- Fixtures ------------------------------------------------------------------------------

    private static List<BookSlot> batch(String... symbols) {
        List<BookSlot> slots = new ArrayList<>();
        for (int i = 0; i < symbols.length; i++) {
            String base = symbols[i].replace("_USDT", "");
            slots.add(new BookSlot(Instrument.of(i, Venue.MEXC_FUTURES, symbols[i], base, "USDT", 0.0001),
                    new OrderBook(0.1), null, null));
        }
        return slots;
    }

    /** Starts a batch and runs virtual time until every send window of it has passed. */
    private CompletableFuture<Void> fetch(List<BookSlot> batch) {
        CompletableFuture<Void> stage = fetcher.fetchAll(batch, outcome).toCompletableFuture();
        scheduler.advanceBy(batch.size() * INTERVAL);
        assertTrue(stage.isDone(), "every slot has an outcome once its send window has passed");
        return stage;
    }

    private List<String> sentSymbols() {
        return sent.stream().map(Sent::symbol).toList();
    }

    private List<Long> sendOffsets() {
        return sent.stream().map(s -> s.atMs() - START).toList();
    }

    private static final class RecordingOutcome implements SnapshotOutcome {
        final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        final List<String> failed = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void delivered(BookSlot slot, String body) {
            delivered.add(slot.instrument().nativeSymbol());
            bodies.add(body);
        }

        @Override
        public void failed(BookSlot slot) {
            failed.add(slot.instrument().nativeSymbol());
        }
    }

    // --- Delivery ------------------------------------------------------------------------------

    @Test
    @DisplayName("a success body is delivered intact, from GET /api/v1/contract/depth/{symbol}?limit=depth-limit")
    void successDelivered() {
        fetch(batch("BTC_USDT"));

        assertEquals(List.of("BTC_USDT"), outcome.delivered);
        assertEquals(List.of(OK_BODY), outcome.bodies, "the strategy parses the envelope-wrapped body itself");
        URI url = sent.getFirst().url();
        assertEquals("/api/v1/contract/depth/BTC_USDT", url.getPath());
        assertEquals("1500", UriComponentsBuilder.fromUri(url).build().getQueryParams().getFirst("limit"));
    }

    @Test
    @DisplayName("fetchAll starts the timers and returns; nothing is sent until time runs")
    void fetchAllDoesNotBlock() {
        CompletableFuture<Void> stage = fetcher.fetchAll(batch("A_USDT", "B_USDT"), outcome).toCompletableFuture();

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
        fetch(batch("A_USDT", "B_USDT", "C_USDT", "D_USDT"));

        assertEquals(List.of("A_USDT", "B_USDT", "C_USDT", "D_USDT"), sentSymbols());
        assertEquals(List.of(0L, 250L, 500L, 750L), sendOffsets());
    }

    @Test
    @DisplayName("the send clock persists across batches: a batch right after another keeps the spacing")
    void spacingPersistsAcrossBatches() {
        CompletableFuture<Void> first = fetcher.fetchAll(batch("A_USDT", "B_USDT"), outcome).toCompletableFuture();
        scheduler.advanceBy(INTERVAL);   // B goes out at +250 and answers at once: the batch is done
        assertTrue(first.isDone());

        fetch(batch("C_USDT", "D_USDT"));

        // A per-batch schedule would have sent C at +250, on top of B.
        assertEquals(List.of(0L, 250L, 500L, 750L), sendOffsets());
    }

    @Test
    @DisplayName("after an idle gap the first send goes out at once")
    void noDelayAfterIdle() {
        fetch(batch("A_USDT"));
        scheduler.advanceBy(10_000);

        fetch(batch("B_USDT"));

        assertEquals(sent.get(0).atMs() + INTERVAL + 10_000, sent.get(1).atMs());
    }

    // --- Throttle (HTTP 200, code 510) ---------------------------------------------------------

    @Test
    @DisplayName("510 mid-batch: failed, never delivered; the rest of the batch fails unsent; cooldown is throttle-cooldown")
    void throttledMidBatch() {
        respond("B_USDT", json(HttpStatus.OK, THROTTLED_BODY));

        fetch(batch("A_USDT", "B_USDT", "C_USDT", "D_USDT"));

        assertEquals(List.of("A_USDT", "B_USDT"), sentSymbols(), "C and D come up inside the cooldown");
        assertEquals(List.of("A_USDT"), outcome.delivered);
        assertEquals(List.of("B_USDT", "C_USDT", "D_USDT"), outcome.failed);
        assertFalse(outcome.bodies.contains(THROTTLED_BODY), "a 510 must never reach the strategy");

        long throttledAt = START + INTERVAL;
        assertFalse(fetcher.isAcceptingRequests());
        scheduler.advanceBy(throttledAt + THROTTLE_COOLDOWN - 1 - scheduler.nowMs());
        assertFalse(fetcher.isAcceptingRequests());
        scheduler.advanceBy(1);
        assertTrue(fetcher.isAcceptingRequests());
    }

    // --- WAF / 429 -----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {403, 429})
    @DisplayName("an HTTP 403 (Akamai, HTML) or 429 fails the rest of the batch and pauses for waf-cooldown")
    void wafBlock(int status) {
        respond("A_USDT", html(HttpStatus.valueOf(status)));

        fetch(batch("A_USDT", "B_USDT"));

        assertEquals(List.of("A_USDT"), sentSymbols());
        assertEquals(List.of("A_USDT", "B_USDT"), outcome.failed);
        scheduler.advanceBy(START + WAF_COOLDOWN - 1 - scheduler.nowMs());
        assertFalse(fetcher.isAcceptingRequests());
        scheduler.advanceBy(1);
        assertTrue(fetcher.isAcceptingRequests());
    }

    @Test
    @DisplayName("a cooldown only extends: a 510 landing during a WAF pause does not shorten it")
    void cooldownNeverShortens() {
        // A is still in flight when B is sent and blocked; A's 510 then lands mid-pause.
        script.put("A_USDT", () -> Mono.delay(Duration.ofMillis(400), scheduler).map(x -> json(HttpStatus.OK, THROTTLED_BODY)));
        respond("B_USDT", html(HttpStatus.FORBIDDEN));

        fetcher.fetchAll(batch("A_USDT", "B_USDT"), outcome);
        scheduler.advanceBy(400);

        assertEquals(List.of("A_USDT", "B_USDT"), outcome.failed.stream().sorted().toList());
        scheduler.advanceBy(START + INTERVAL + WAF_COOLDOWN - 1 - scheduler.nowMs());
        assertFalse(fetcher.isAcceptingRequests(), "still inside the 90s WAF pause");
        scheduler.advanceBy(1);
        assertTrue(fetcher.isAcceptingRequests());
    }

    // --- Other failures: reported failed, no cooldown ------------------------------------------

    @Test
    @DisplayName("another error status fails its slot only; no cooldown, the rest of the batch delivers")
    void otherStatusNoCooldown() {
        respond("A_USDT", json(HttpStatus.INTERNAL_SERVER_ERROR, "{}"));

        fetch(batch("A_USDT", "B_USDT"));

        assertEquals(List.of("A_USDT"), outcome.failed);
        assertEquals(List.of("B_USDT"), outcome.delivered);
        assertTrue(fetcher.isAcceptingRequests());
    }

    @Test
    @DisplayName("a connection error fails its slot only; no cooldown")
    void connectionErrorNoCooldown() {
        script.put("A_USDT", () -> Mono.error(new IOException("Connection reset")));

        fetch(batch("A_USDT", "B_USDT"));

        assertEquals(List.of("A_USDT"), outcome.failed);
        assertEquals(List.of("B_USDT"), outcome.delivered);
        assertTrue(fetcher.isAcceptingRequests());
    }

    @Test
    @DisplayName("success:false with a code other than 510 fails its slot without a cooldown")
    void otherApiErrorNoCooldown() {
        respond("A_USDT", json(HttpStatus.OK, "{\"success\":false,\"code\":1001,\"message\":\"contract not exist\"}"));

        fetch(batch("A_USDT", "B_USDT"));

        assertEquals(List.of("A_USDT"), outcome.failed);
        assertEquals(List.of("B_USDT"), outcome.delivered);
        assertTrue(fetcher.isAcceptingRequests());
    }

    @Test
    @DisplayName("an unreadable or empty 200 body is a failure, never a delivery")
    void unreadableBodyFailed() {
        respond("A_USDT", html(HttpStatus.OK));
        respond("B_USDT", ClientResponse.create(HttpStatus.OK).build());

        fetch(batch("A_USDT", "B_USDT"));

        assertEquals(List.of("A_USDT", "B_USDT"), outcome.failed);
        assertTrue(outcome.delivered.isEmpty());
        assertTrue(fetcher.isAcceptingRequests());
    }

    // --- Envelope classification ---------------------------------------------------------------

    @Test
    @DisplayName("classify stops at success:true — the levels after it are never parsed")
    void classifyStopsAtSuccess() {
        // Truncated mid-data: a full parse would fail.
        assertEquals(BodyKind.OK, MexcSnapshotFetcher.classify("{\"success\":true,\"code\":0,\"data\":{\"asks\":[[1,"));
    }

    @Test
    @DisplayName("classify does not depend on field order")
    void classifyAnyOrder() {
        assertEquals(BodyKind.THROTTLED, MexcSnapshotFetcher.classify("{\"code\":510,\"message\":\"x\",\"success\":false}"));
        assertEquals(BodyKind.OK, MexcSnapshotFetcher.classify("{\"data\":{\"asks\":[],\"bids\":[]},\"code\":0,\"success\":true}"));
    }

    @Test
    @DisplayName("classify: other failure codes are REJECTED; anything without a boolean success is MALFORMED")
    void classifyOther() {
        assertEquals(BodyKind.REJECTED, MexcSnapshotFetcher.classify("{\"success\":false,\"code\":1001}"));
        assertEquals(BodyKind.REJECTED, MexcSnapshotFetcher.classify("{\"success\":false}"));
        assertEquals(BodyKind.MALFORMED, MexcSnapshotFetcher.classify("{\"code\":0,\"data\":{}}"));
        assertEquals(BodyKind.MALFORMED, MexcSnapshotFetcher.classify("{\"success\":\"true\"}"));
        assertEquals(BodyKind.MALFORMED, MexcSnapshotFetcher.classify("[1,2]"));
        assertEquals(BodyKind.MALFORMED, MexcSnapshotFetcher.classify("<HTML></HTML>"));
        assertEquals(BodyKind.MALFORMED, MexcSnapshotFetcher.classify(""));
    }
}
