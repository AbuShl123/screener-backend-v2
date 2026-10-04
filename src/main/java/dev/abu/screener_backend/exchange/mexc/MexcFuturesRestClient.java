package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.mexc.dto.MexcContractDto;
import dev.abu.screener_backend.exchange.mexc.dto.MexcResponse;
import dev.abu.screener_backend.exchange.rest.ExchangeApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Typed REST client for MEXC's futures (contract) API. Built by {@link MexcAdapterConfig}.
 *
 * <p>Futures-only by design, unlike {@code BinanceRestClient}, which serves both Binance venues
 * through a {@code BinancePaths} parameter. MEXC spot is a different API, not the same one under
 * another prefix: no {@code {success, code, data}} envelope, different DTOs and symbol format, and
 * the depth symbol is a query parameter rather than a path segment. Spot gets its own client.
 *
 * <p>All methods return cold {@link Mono} publishers. Failures travel the error channel, logged at
 * {@code WARN} first, in one of two shapes:
 * <ul>
 *   <li>{@link ExchangeApiException} — a non-2xx status, including an Akamai WAF block (HTML 403);</li>
 *   <li>{@link MexcApiException} — a 2xx whose envelope says {@code "success": false}, e.g. a
 *       throttled request ({@code code 510}).</li>
 * </ul>
 */
@Slf4j
public class MexcFuturesRestClient {

    private static final Venue VENUE = Venue.MEXC_FUTURES;
    private static final String CONTRACT_DETAIL_PATH = "/api/v1/contract/detail";
    private static final ParameterizedTypeReference<MexcResponse<List<MexcContractDto>>> CONTRACT_DETAIL_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public MexcFuturesRestClient(WebClient webClient) {
        this.webClient = webClient;
    }

    /** Every contract MEXC lists, of every quote coin, type and state — unfiltered. */
    public Mono<List<MexcContractDto>> contractDetail() {
        return logErrors(retrieve(CONTRACT_DETAIL_PATH).bodyToMono(CONTRACT_DETAIL_TYPE).map(this::unwrap),
                CONTRACT_DETAIL_PATH);
    }

    private <T> T unwrap(MexcResponse<T> response) {
        if (!response.success()) {
            throw new MexcApiException(VENUE, response.code(), response.message());
        }
        if (response.data() == null) {
            throw new IllegalStateException(VENUE + " response reports success but carries no data");
        }
        return response.data();
    }

    private WebClient.ResponseSpec retrieve(String uriTemplate, Object... uriVariables) {
        return webClient.get()
                .uri(uriTemplate, uriVariables)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> ExchangeApiException.from(VENUE, response));
    }

    private static <T> Mono<T> logErrors(Mono<T> call, String path) {
        return call.doOnError(ex -> log.warn("[{}] REST call failed [{}]: {}", VENUE, path, ex.getMessage()));
    }
}
