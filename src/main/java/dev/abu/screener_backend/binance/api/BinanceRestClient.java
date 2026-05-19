package dev.abu.screener_backend.binance.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Generic, reusable HTTP client for the Binance REST API.
 *
 * <p>Provides separate access paths for the Spot and Futures APIs, each backed by a
 * dedicated {@link WebClient} pre-configured with the appropriate base URL. All methods
 * return cold {@link Mono} publishers — callers decide whether to
 * {@link Mono#block() block} (acceptable in scheduled/MVC contexts) or subscribe reactively.
 *
 * <p>This client is intentionally thin. Business logic (ticker filtering, rate-limit
 * scheduling, retry policies) belongs in callers, not here.
 *
 * <p>Non-2xx responses are wrapped in {@link BinanceApiException} and propagated through
 * the Mono error channel. All errors are logged at {@code WARN} level before propagation.
 */
@Slf4j
@Component
public class BinanceRestClient {

    private final WebClient spotClient;
    private final WebClient futuresClient;

    public BinanceRestClient(
            @Qualifier("spotWebClient") WebClient spotClient,
            @Qualifier("futuresWebClient") WebClient futuresClient) {
        this.spotClient = spotClient;
        this.futuresClient = futuresClient;
    }

    /**
     * Issues a GET request to the Binance Spot REST API and deserializes the response.
     *
     * @param path         URI path relative to the spot base URL, e.g. {@code "/api/v3/exchangeInfo"}
     * @param responseType target deserialization class
     * @param <T>          response type
     * @return Mono emitting the deserialized response, or an error Mono on failure
     */
    public <T> Mono<T> getSpot(String path, Class<T> responseType) {
        return get(spotClient, path, responseType);
    }

    /**
     * Issues a GET request to the Binance Futures REST API and deserializes the response.
     *
     * @param path         URI path relative to the futures base URL, e.g. {@code "/fapi/v1/exchangeInfo"}
     * @param responseType target deserialization class
     * @param <T>          response type
     * @return Mono emitting the deserialized response, or an error Mono on failure
     */
    public <T> Mono<T> getFutures(String path, Class<T> responseType) {
        return get(futuresClient, path, responseType);
    }

    private <T> Mono<T> get(WebClient client, String path, Class<T> responseType) {
        return client.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toApiException)
                .bodyToMono(responseType)
                .doOnError(ex -> log.warn("Binance REST call failed [{}]: {}", path, ex.getMessage()));
    }

    private Mono<? extends Throwable> toApiException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new BinanceApiException(response.statusCode(), body));
    }
}
