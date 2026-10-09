package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcDepthClient.BodyKind;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcSpotSymbolDto;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.fixtureText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link MexcSpotRestClient} driven through a real WebClient and JSON decoder. */
class MexcSpotRestClientTest {

    private final AtomicReference<URI> sent = new AtomicReference<>();

    private MexcSpotRestClient client(ClientResponse response) {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.mexc.com")
                .exchangeFunction(request -> {
                    sent.set(request.url());
                    return Mono.just(response);
                })
                .build();
        return new MexcSpotRestClient(webClient);
    }

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

    @Test
    @DisplayName("serves MEXC spot")
    void venue() {
        assertEquals(Venue.MEXC_SPOT, new MexcSpotRestClient(null).venue());
    }

    // --- exchangeInfo --------------------------------------------------------------------------

    @Test
    @DisplayName("exchangeInfo: returns the symbol list, isSpotTradingAllowed and string status included")
    void exchangeInfo() {
        List<MexcSpotSymbolDto> symbols = client(json(HttpStatus.OK, """
                {"timezone":"CST","serverTime":1791556844300,"rateLimits":[],"exchangeFilters":[],"symbols":[
                 {"symbol":"BTCUSDT","status":"1","baseAsset":"BTC","baseAssetPrecision":8,"quoteAsset":"USDT",
                  "isSpotTradingAllowed":true,"isMarginTradingAllowed":false,"permissions":["SPOT"]}]}
                """)).exchangeInfo().block();

        assertEquals("https://api.mexc.com/api/v3/exchangeInfo", sent.get().toASCIIString());
        assertEquals(List.of(new MexcSpotSymbolDto("BTCUSDT", "1", "BTC", "USDT", true)), symbols);
    }

    @Test
    @DisplayName("exchangeInfo: a body without a symbols array is a changed shape and fails")
    void exchangeInfoNoSymbols() {
        MexcSpotRestClient client = client(json(HttpStatus.OK, "{\"timezone\":\"CST\"}"));

        assertThrows(IllegalStateException.class, () -> client.exchangeInfo().block());
    }

    @Test
    @DisplayName("exchangeInfo: an Akamai HTML 403 is an ExchangeApiException carrying the status")
    void exchangeInfoWafBlock() {
        ExchangeApiException e = assertThrows(ExchangeApiException.class,
                () -> client(html(HttpStatus.FORBIDDEN)).exchangeInfo().block());

        assertEquals(403, e.getStatusCode().value());
    }

    // --- depth ---------------------------------------------------------------------------------

    @Test
    @DisplayName("depth: returns the raw body, symbol and limit in the query")
    void depthRawBody() {
        String body = fixtureText("snapshot-mintusdt-44408969.json");

        String result = client(json(HttpStatus.OK, body)).depth("BTCUSDT", 2000).block();

        assertEquals("https://api.mexc.com/api/v3/depth?symbol=BTCUSDT&limit=2000", sent.get().toASCIIString());
        assertEquals(body, result);
    }

    @Test
    @DisplayName("depth: a CJK symbol is percent-encoded exactly once")
    void depthCjkSymbol() {
        client(json(HttpStatus.OK, "{}")).depth("龙虾USDT", 2000).block();

        assertEquals("https://api.mexc.com/api/v3/depth?symbol=%E9%BE%99%E8%99%BEUSDT&limit=2000",
                sent.get().toASCIIString());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 429})
    @DisplayName("depth: an unknown symbol (400), a WAF block (403) or a rate limit (429) is an ExchangeApiException")
    void depthErrorStatus(int status) {
        MexcSpotRestClient client = client(status == 403
                ? html(HttpStatus.FORBIDDEN)
                : json(HttpStatus.valueOf(status), "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}"));

        ExchangeApiException e = assertThrows(ExchangeApiException.class, () -> client.depth("NOPEUSDT", 2000).block());

        assertEquals(status, e.getStatusCode().value());
    }

    // --- Depth body classification ------------------------------------------------------------

    @Test
    @DisplayName("classify: a captured snapshot is OK")
    void classifyFixture() {
        assertEquals(BodyKind.OK, MexcSpotRestClient.classify(fixtureText("snapshot-mintusdt-44408969.json")));
    }

    @Test
    @DisplayName("classify stops at lastUpdateId — the levels after it are never parsed")
    void classifyStopsAtLastUpdateId() {
        // Truncated mid-bids: a full parse would fail.
        assertEquals(BodyKind.OK, MexcSpotRestClient.classify("{\"lastUpdateId\":42,\"bids\":[[\"1\","));
    }

    @Test
    @DisplayName("classify does not depend on field order")
    void classifyAnyOrder() {
        assertEquals(BodyKind.OK, MexcSpotRestClient.classify("{\"bids\":[],\"asks\":[],\"lastUpdateId\":42}"));
        assertEquals(BodyKind.REJECTED, MexcSpotRestClient.classify("{\"msg\":\"x\",\"code\":-1121}"));
    }

    @Test
    @DisplayName("classify: never THROTTLED; anything without a numeric lastUpdateId or a code is MALFORMED")
    void classifyOther() {
        assertEquals(BodyKind.REJECTED, MexcSpotRestClient.classify("{\"code\":429,\"msg\":\"Too Many Requests\"}"));
        assertEquals(BodyKind.MALFORMED, MexcSpotRestClient.classify("{\"lastUpdateId\":\"42\"}"));
        assertEquals(BodyKind.MALFORMED, MexcSpotRestClient.classify("{\"bids\":[],\"asks\":[]}"));
        assertEquals(BodyKind.MALFORMED, MexcSpotRestClient.classify("[1,2]"));
        assertEquals(BodyKind.MALFORMED, MexcSpotRestClient.classify("<HTML></HTML>"));
        assertEquals(BodyKind.MALFORMED, MexcSpotRestClient.classify(""));
        assertEquals(BodyKind.MALFORMED, MexcSpotRestClient.classify(null));
    }
}
