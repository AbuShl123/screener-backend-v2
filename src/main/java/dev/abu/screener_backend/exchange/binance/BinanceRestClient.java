package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.binance.dto.ExchangeInfoResponse;
import dev.abu.screener_backend.exchange.rest.ExchangeApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Arrays;

/**
 * Typed REST client for one Binance venue (spot or futures). Built twice — once per venue, each
 * with its own {@link WebClient} and {@link BinancePaths} — by {@link BinanceAdapterConfig}.
 *
 * <p>All methods return cold {@link Mono} publishers — callers decide whether to
 * {@link Mono#block() block} (acceptable in scheduled/MVC contexts) or subscribe reactively.
 *
 * <p>Non-2xx responses are wrapped in {@link ExchangeApiException} and propagated through the Mono
 * error channel. All errors are logged at {@code WARN} level before propagation.
 */
@Slf4j
public class BinanceRestClient {

    private final Venue venue;
    private final WebClient webClient;
    private final BinancePaths paths;

    public BinanceRestClient(Venue venue, WebClient webClient, BinancePaths paths) {
        this.venue = venue;
        this.webClient = webClient;
        this.paths = paths;
    }

    /** Issues a GET to this venue's {@code exchangeInfo} endpoint. */
    public Mono<ExchangeInfoResponse> exchangeInfo() {
        return get(ExchangeInfoResponse.class, paths.exchangeInfoPath());
    }

    /**
     * Issues a GET to this venue's depth endpoint. Returns the raw body — the sync strategy parses it.
     *
     * <p>The symbol is passed as a URI variable, never pre-encoded into the template: WebClient
     * encodes the template itself, so a pre-encoded string would be encoded twice and non-ASCII
     * symbols (e.g. {@code 牛来USDT}) would reach Binance as {@code %25E7...} → {@code -1121}.
     */
    public Mono<String> depth(String symbol, int limit) {
        return get(String.class, paths.depthPath() + "?symbol={symbol}&limit={limit}", symbol, limit);
    }

    private <T> Mono<T> get(Class<T> responseType, String uriTemplate, Object... uriVariables) {
        return webClient.get()
                .uri(uriTemplate, uriVariables)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toApiException)
                .bodyToMono(responseType)
                .doOnError(ex -> log.warn("[{}] REST call failed [{} {}]: {}",
                        venue, uriTemplate, Arrays.toString(uriVariables), ex.getMessage()));
    }

    private Mono<? extends Throwable> toApiException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExchangeApiException(venue, response.statusCode(), body));
    }
}
