package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.binance.dto.ExchangeInfoResponse;
import dev.abu.screener_backend.exchange.rest.ExchangeApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
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

    public Venue venue() {
        return venue;
    }

    /** Issues a GET to this venue's {@code exchangeInfo} endpoint. */
    public Mono<ExchangeInfoResponse> exchangeInfo() {
        String uriTemplate = paths.exchangeInfoPath();
        return logErrors(retrieve(uriTemplate).bodyToMono(ExchangeInfoResponse.class), uriTemplate);
    }

    /**
     * Issues a GET to this venue's depth endpoint. Returns the raw body — the sync strategy parses
     * it — together with the response headers, which carry Binance's weight counter.
     *
     * <p>The symbol is passed as a URI variable, never pre-encoded into the template: WebClient
     * encodes the template itself, so a pre-encoded string would be encoded twice and non-ASCII
     * symbols (e.g. {@code 牛来USDT}) would reach Binance as {@code %25E7...} → {@code -1121}.
     */
    public Mono<ResponseEntity<String>> depth(String symbol, int limit) {
        String uriTemplate = paths.depthPath() + "?symbol={symbol}&limit={limit}";
        return logErrors(retrieve(uriTemplate, symbol, limit).toEntity(String.class), uriTemplate, symbol, limit);
    }

    private WebClient.ResponseSpec retrieve(String uriTemplate, Object... uriVariables) {
        return webClient.get()
                .uri(uriTemplate, uriVariables)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toApiException);
    }

    private <T> Mono<T> logErrors(Mono<T> call, String uriTemplate, Object... uriVariables) {
        return call.doOnError(ex -> log.warn("[{}] REST call failed [{} {}]: {}",
                venue, uriTemplate, Arrays.toString(uriVariables), ex.getMessage()));
    }

    /**
     * {@code defaultIfEmpty} matters: an error status with an empty body would otherwise map to an
     * empty {@code Mono}, which {@code onStatus} treats as "not an error" — and a bodiless 429
     * would then be handed to the caller as a success.
     */
    private Mono<? extends Throwable> toApiException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new ExchangeApiException(venue, response.statusCode(),
                        response.headers().asHttpHeaders(), body));
    }
}
