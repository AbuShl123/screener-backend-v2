package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcContractDto;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcResponse;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

import java.util.List;
import java.util.Objects;

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
 * {@link #depth} is the exception to the second: it returns the raw body, envelope included, and
 * leaves classifying it to {@link #classifyDepth}.
 */
@Slf4j
public class MexcFuturesRestClient implements MexcDepthClient {

    private static final Venue VENUE = Venue.MEXC_FUTURES;
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();
    private static final int THROTTLED_CODE = 510;
    private static final String CONTRACT_DETAIL_PATH = "/api/v1/contract/detail";
    private static final String DEPTH_PATH = "/api/v1/contract/depth/{symbol}?limit={limit}";
    private static final ParameterizedTypeReference<MexcResponse<List<MexcContractDto>>> CONTRACT_DETAIL_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public MexcFuturesRestClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Venue venue() {
        return VENUE;
    }

    /** Every contract MEXC lists, of every quote coin, type and state — unfiltered. */
    public Mono<List<MexcContractDto>> contractDetail() {
        return logErrors(retrieve(CONTRACT_DETAIL_PATH).bodyToMono(CONTRACT_DETAIL_TYPE).map(this::unwrap),
                CONTRACT_DETAIL_PATH);
    }

    /**
     * One contract's order book, as the raw body — not unwrapped through {@link MexcResponse}: the
     * sync strategy stream-parses it, and {@code MexcSnapshotFetcher} reads its envelope first. So a
     * throttled request ({@code 200}, {@code success:false}) arrives here as a value, not an error.
     *
     * <p>The symbol is a URI variable, never pre-encoded into the path, as in {@code BinanceRestClient}.
     */
    @Override
    public Mono<String> depth(String symbol, int limit) {
        return logErrors(retrieve(DEPTH_PATH, symbol, limit).bodyToMono(String.class), DEPTH_PATH + " " + symbol);
    }

    /**
     * Reads the envelope only: returns as soon as {@code success} is {@code true}, so {@code data}
     * is never tokenized when MEXC sends {@code success} first (it does). Indifferent to field
     * order otherwise. {@code success:false} with {@code code 510} is {@code THROTTLED}, with any
     * other code {@code REJECTED}.
     */
    @Override
    public BodyKind classifyDepth(String body) {
        return classify(body);
    }

    static BodyKind classify(String body) {
        if (body == null || body.isEmpty()) return BodyKind.MALFORMED;
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return BodyKind.MALFORMED;
            boolean failed = false;
            int code = -1;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if (field.equals("success")) {
                    if (value == JsonToken.VALUE_TRUE) return BodyKind.OK;
                    if (value != JsonToken.VALUE_FALSE) return BodyKind.MALFORMED;
                    failed = true;
                    if (code != -1) break;
                } else if (field.equals("code") && value == JsonToken.VALUE_NUMBER_INT) {
                    code = p.getIntValue();
                    if (failed) break;
                } else {
                    p.skipChildren();
                }
            }
            if (!failed) return BodyKind.MALFORMED;
            return code == THROTTLED_CODE ? BodyKind.THROTTLED : BodyKind.REJECTED;
        } catch (JacksonException e) {
            return BodyKind.MALFORMED;   // e.g. an HTML page with a 200
        }
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
        return call.doOnError(ex -> log.warn("[{}] REST call failed [{}]: {}", VENUE, path,
                Objects.toString(ex.getMessage(), ex.getClass().getSimpleName())));
    }
}
