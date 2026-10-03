package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.rest.ExchangeApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The URL {@link BinanceRestClient} actually puts on the wire, captured below the WebClient. */
class BinanceRestClientTest {

    private final AtomicReference<URI> sent = new AtomicReference<>();

    private BinanceRestClient client() {
        return client(ClientResponse.create(HttpStatus.OK).body("{}").build());
    }

    private BinanceRestClient client(ClientResponse response) {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://fapi.binance.com")
                .exchangeFunction(request -> {
                    sent.set(request.url());
                    return Mono.just(response);
                })
                .build();
        return new BinanceRestClient(Venue.BINANCE_FUTURES, webClient, BinancePaths.FUTURES);
    }

    @Test
    @DisplayName("depth: a success carries its headers, so the weight counter reaches the fetcher")
    void depthCarriesHeaders() {
        ResponseEntity<String> response = client(ClientResponse.create(HttpStatus.OK)
                .header("x-mbx-used-weight-1m", "42").body("{\"lastUpdateId\":1}").build())
                .depth("BTCUSDT", 1000).block();

        assertEquals("42", response.getHeaders().getFirst("x-mbx-used-weight-1m"));
        assertEquals("{\"lastUpdateId\":1}", response.getBody());
    }

    @Test
    @DisplayName("depth: an error, even with an empty body, is an ExchangeApiException carrying the headers")
    void depthErrorCarriesHeaders() {
        BinanceRestClient client = client(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "8").build());

        ExchangeApiException e = assertThrows(ExchangeApiException.class, () -> client.depth("BTCUSDT", 1000).block());

        assertEquals(429, e.getStatusCode().value());
        assertEquals("8", e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("depth: ASCII symbol is sent as-is")
    void depthAsciiSymbol() {
        client().depth("BTCUSDT", 1000).block();

        assertEquals("https://fapi.binance.com/fapi/v1/depth?symbol=BTCUSDT&limit=1000",
                sent.get().toASCIIString());
    }

    @Test
    @DisplayName("depth: non-ASCII symbol is percent-encoded exactly once")
    void depthNonAsciiSymbolEncodedOnce() {
        client().depth("牛来USDT", 1000).block();

        assertEquals("https://fapi.binance.com/fapi/v1/depth?symbol=%E7%89%9B%E6%9D%A5USDT&limit=1000",
                sent.get().toASCIIString());
    }
}
