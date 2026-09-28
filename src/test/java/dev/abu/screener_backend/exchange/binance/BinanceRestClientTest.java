package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The URL {@link BinanceRestClient} actually puts on the wire, captured below the WebClient. */
class BinanceRestClientTest {

    private final AtomicReference<URI> sent = new AtomicReference<>();

    private BinanceRestClient client() {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://fapi.binance.com")
                .exchangeFunction(request -> {
                    sent.set(request.url());
                    return Mono.just(ClientResponse.create(HttpStatus.OK).body("{}").build());
                })
                .build();
        return new BinanceRestClient(Venue.BINANCE_FUTURES, webClient, BinancePaths.FUTURES);
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
