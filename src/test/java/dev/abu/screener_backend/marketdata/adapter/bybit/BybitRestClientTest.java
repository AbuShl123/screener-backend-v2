package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.bybit.dto.BybitInstrumentDto;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link BybitRestClient}'s envelope handling and pagination, driven through a real WebClient and
 * JSON decoder, with captured {@code instruments-info} payloads where the shape matters.
 */
class BybitRestClientTest {

    private static final String BASE_URL = "https://api.bybit.com";
    private static final String RATE_LIMITED = """
            {"retCode":10006,"retMsg":"Too many visits!","result":{},"retExtInfo":{},"time":1785668378332}
            """;

    private final List<URI> sent = new ArrayList<>();

    /** A client whose every request is answered by {@code responder}, given the request URL. */
    private BybitRestClient client(Venue venue, Function<URI, ClientResponse> responder) {
        WebClient webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .exchangeFunction(request -> {
                    sent.add(request.url());
                    return Mono.just(responder.apply(request.url()));
                })
                .build();
        return new BybitRestClient(venue, webClient);
    }

    private BybitRestClient client(Venue venue, HttpStatus status, String body) {
        return client(venue, url -> json(status, body));
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static String page(String nextPageCursorField, String... symbols) {
        StringBuilder list = new StringBuilder();
        for (String symbol : symbols) {
            if (!list.isEmpty()) list.append(',');
            list.append("{\"symbol\":\"").append(symbol).append("\",\"contractType\":\"LinearPerpetual\",")
                    .append("\"status\":\"Trading\",\"baseCoin\":\"X\",\"quoteCoin\":\"USDT\"}");
        }
        return "{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{\"category\":\"linear\",\"list\":[" + list + "]"
                + nextPageCursorField + "},\"retExtInfo\":{},\"time\":1}";
    }

    static String fixture(String name) {
        try (InputStream in = BybitRestClientTest.class.getResourceAsStream("/bybit/" + name)) {
            if (in == null) throw new IllegalStateException("Missing fixture bybit/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> symbols(List<BybitInstrumentDto> rows) {
        return rows.stream().map(BybitInstrumentDto::symbol).toList();
    }

    // --- Construction --------------------------------------------------------------------------

    @Test
    @DisplayName("spot maps to category=spot, futures to category=linear; a non-Bybit venue is rejected")
    void category() {
        assertEquals("spot", BybitRestClient.category(Venue.BYBIT_SPOT));
        assertEquals("linear", BybitRestClient.category(Venue.BYBIT_FUTURES));
        assertThrows(IllegalArgumentException.class, () -> new BybitRestClient(Venue.MEXC_SPOT, null));
    }

    // --- instruments-info ----------------------------------------------------------------------

    @Test
    @DisplayName("instrumentsInfo (spot): one request; a body without nextPageCursor ends pagination")
    void spotSinglePage() {
        List<BybitInstrumentDto> rows = client(Venue.BYBIT_SPOT, HttpStatus.OK, fixture("instruments-info-spot.json"))
                .instrumentsInfo().block();

        assertEquals(List.of(URI.create(BASE_URL + "/v5/market/instruments-info?category=spot&limit=1000")), sent);
        assertEquals(List.of("BTCUSDT", "ETHUSDT", "ETHBTC", "BTCUSDC", "CTCUSDT", "ARBUSDT"), symbols(rows));
        assertEquals(new BybitInstrumentDto("CTCUSDT", null, "Trading", "CTC", "USDT", "1", ""), rows.get(4));
    }

    @Test
    @DisplayName("instrumentsInfo (linear): an empty nextPageCursor ends pagination; linear fields are mapped")
    void linearSinglePage() {
        List<BybitInstrumentDto> rows = client(Venue.BYBIT_FUTURES, HttpStatus.OK, fixture("instruments-info-linear.json"))
                .instrumentsInfo().block();

        assertEquals(List.of(URI.create(BASE_URL + "/v5/market/instruments-info?category=linear&limit=1000")), sent);
        assertEquals(6, rows.size());
        assertEquals(new BybitInstrumentDto("1000000BABYDOGEUSDT", "LinearPerpetual", "Trading", "1000000BABYDOGE",
                "USDT", null, ""), rows.getFirst());
        assertEquals("LinearFutures", rows.getLast().contractType());
    }

    @Test
    @DisplayName("instrumentsInfo (linear): follows nextPageCursor, sending it back exactly as Bybit sent it")
    void linearPaginates() {
        BybitRestClient client = client(Venue.BYBIT_FUTURES, url -> json(HttpStatus.OK,
                url.getRawQuery().contains("cursor=")
                        ? page(",\"nextPageCursor\":\"\"", "CCCUSDT")
                        : page(",\"nextPageCursor\":\"first%3D0GUSDT%26last%3DAXSUSDT\"", "AAAUSDT", "BBBUSDT")));

        List<BybitInstrumentDto> rows = client.instrumentsInfo().block();

        assertEquals(List.of("AAAUSDT", "BBBUSDT", "CCCUSDT"), symbols(rows));
        assertEquals(2, sent.size());
        assertEquals(BASE_URL + "/v5/market/instruments-info?category=linear&limit=1000"
                + "&cursor=first%3D0GUSDT%26last%3DAXSUSDT", sent.get(1).toASCIIString());
    }

    @Test
    @DisplayName("instrumentsInfo: a cursor that never ends fails instead of looping or truncating")
    void runawayCursorFails() {
        BybitRestClient client = client(Venue.BYBIT_FUTURES,
                url -> json(HttpStatus.OK, page(",\"nextPageCursor\":\"again\"", "AAAUSDT")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.instrumentsInfo().block());

        assertEquals("BYBIT_FUTURES instruments-info did not end within 10 pages", e.getMessage());
        assertEquals(BybitRestClient.MAX_INSTRUMENT_PAGES + 1, sent.size());
    }

    @Test
    @DisplayName("instrumentsInfo: a rate limit in an HTTP 200 (retCode 10006) is a BybitApiException, not data")
    void rateLimitedIn200() {
        BybitRestClient client = client(Venue.BYBIT_SPOT, HttpStatus.OK, RATE_LIMITED);

        BybitApiException e = assertThrows(BybitApiException.class, () -> client.instrumentsInfo().block());

        assertEquals(10006, e.getRetCode());
        assertEquals(Venue.BYBIT_SPOT, e.getVenue());
    }

    @Test
    @DisplayName("instrumentsInfo: an error on a later page fails the whole call")
    void laterPageError() {
        BybitRestClient client = client(Venue.BYBIT_FUTURES, url -> json(HttpStatus.OK,
                url.getRawQuery().contains("cursor=") ? RATE_LIMITED : page(",\"nextPageCursor\":\"next\"", "AAAUSDT")));

        assertThrows(BybitApiException.class, () -> client.instrumentsInfo().block());
    }

    @Test
    @DisplayName("instrumentsInfo: no retCode, no result or no list is a changed shape and fails")
    void changedShape() {
        assertThrows(IllegalStateException.class, () -> client(Venue.BYBIT_SPOT, HttpStatus.OK,
                "{\"retMsg\":\"OK\",\"result\":{\"list\":[]}}").instrumentsInfo().block());
        assertThrows(IllegalStateException.class, () -> client(Venue.BYBIT_SPOT, HttpStatus.OK,
                "{\"retCode\":0,\"retMsg\":\"OK\"}").instrumentsInfo().block());
        assertThrows(IllegalStateException.class, () -> client(Venue.BYBIT_SPOT, HttpStatus.OK,
                "{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{\"category\":\"spot\"}}").instrumentsInfo().block());
    }

    @Test
    @DisplayName("instrumentsInfo: an HTTP 403 (IP ban, geo-block) is an ExchangeApiException carrying the status")
    void forbidden() {
        BybitRestClient client = client(Venue.BYBIT_SPOT, url -> ClientResponse.create(HttpStatus.FORBIDDEN)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                .body("<html><body>403 Forbidden</body></html>")
                .build());

        ExchangeApiException e = assertThrows(ExchangeApiException.class, () -> client.instrumentsInfo().block());

        assertEquals(403, e.getStatusCode().value());
    }

    // --- depth ---------------------------------------------------------------------------------

    @Test
    @DisplayName("depth: returns the raw body, with category, symbol and limit in the query")
    void depthRawBody() {
        String body = fixture("rest-orderbook-linear-ARBUSDT-1000.json");

        String result = client(Venue.BYBIT_FUTURES, HttpStatus.OK, body).depth("ARBUSDT", 1000).block();

        assertEquals(BASE_URL + "/v5/market/orderbook?category=linear&symbol=ARBUSDT&limit=1000",
                sent.getFirst().toASCIIString());
        assertEquals(body, result);
    }

    @Test
    @DisplayName("depth: a rate limit in an HTTP 200 is returned as a body, for the fetcher to classify")
    void depthRateLimitedIsAValue() {
        assertEquals(RATE_LIMITED, client(Venue.BYBIT_SPOT, HttpStatus.OK, RATE_LIMITED).depth("ARBUSDT", 1000).block());
    }

    @Test
    @DisplayName("depth: an HTTP 403 is an ExchangeApiException")
    void depthForbidden() {
        BybitRestClient client = client(Venue.BYBIT_SPOT, HttpStatus.FORBIDDEN, "{\"retCode\":403,\"retMsg\":\"access too frequent\"}");

        ExchangeApiException e = assertThrows(ExchangeApiException.class, () -> client.depth("ARBUSDT", 1000).block());

        assertEquals(403, e.getStatusCode().value());
    }
}
