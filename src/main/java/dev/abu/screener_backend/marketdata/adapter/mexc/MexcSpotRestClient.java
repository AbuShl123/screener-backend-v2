package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcSpotExchangeInfo;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcSpotSymbolDto;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeApiException;
import lombok.extern.slf4j.Slf4j;
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
 * Typed REST client for MEXC's spot API ({@code /api/v3}). Built by {@link MexcAdapterConfig}.
 *
 * <p>Separate from {@link MexcFuturesRestClient} because spot is a different API (progress doc
 * decision 20): no {@code {success, code, data}} envelope, {@code ETHUSDT} rather than
 * {@code ETH_USDT}, and the depth symbol is a query parameter.
 *
 * <p>All methods return cold {@link Mono} publishers. Every failure is a non-2xx status, wrapped in
 * {@link ExchangeApiException} and logged at {@code WARN}: an Akamai WAF block is an HTML 403, an
 * origin rate limit a 429 ({@code {"code":429,…}}), an unknown symbol a 400 ({@code {"code":-1121,…}})
 * — {@code external-docs/mexc/mexc-api-rate-limits-empirical.md}.
 */
@Slf4j
public class MexcSpotRestClient implements MexcDepthClient {

    private static final Venue VENUE = Venue.MEXC_SPOT;
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();
    private static final String EXCHANGE_INFO_PATH = "/api/v3/exchangeInfo";
    private static final String DEPTH_PATH = "/api/v3/depth?symbol={symbol}&limit={limit}";

    private final WebClient webClient;

    public MexcSpotRestClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Venue venue() {
        return VENUE;
    }

    /**
     * Every spot pair MEXC lists, of every quote asset and state — unfiltered.
     *
     * @throws IllegalStateException (in the {@code Mono}) if the response has no symbol list: a
     *                               changed shape, not "MEXC lists nothing"
     */
    public Mono<List<MexcSpotSymbolDto>> exchangeInfo() {
        Mono<List<MexcSpotSymbolDto>> symbols = retrieve(EXCHANGE_INFO_PATH)
                .bodyToMono(MexcSpotExchangeInfo.class)
                .map(info -> {
                    if (info.symbols() == null) {
                        throw new IllegalStateException(VENUE + " exchangeInfo response has no symbols array");
                    }
                    return info.symbols();
                });
        return logErrors(symbols, EXCHANGE_INFO_PATH);
    }

    /**
     * One pair's order book, as the raw body ({@code {"lastUpdateId":…,"bids":…,"asks":…}}): the
     * sync strategy stream-parses it.
     *
     * <p>The symbol is a URI variable, never pre-encoded into the path, as in {@code BinanceRestClient}:
     * WebClient then percent-encodes CJK symbols ({@code 龙虾USDT}) exactly once.
     */
    @Override
    public Mono<String> depth(String symbol, int limit) {
        return logErrors(retrieve(DEPTH_PATH, symbol, limit).bodyToMono(String.class), DEPTH_PATH + " " + symbol);
    }

    /**
     * {@code OK} once a numeric {@code lastUpdateId} is read — it is the first field, so the levels
     * are never tokenized. A 2xx carrying {@code code} instead is {@code REJECTED}; spot has no
     * throttle-in-a-200, so never {@code THROTTLED}.
     */
    @Override
    public BodyKind classifyDepth(String body) {
        return classify(body);
    }

    static BodyKind classify(String body) {
        if (body == null || body.isEmpty()) return BodyKind.MALFORMED;
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return BodyKind.MALFORMED;
            boolean hasCode = false;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if (field.equals("lastUpdateId")) {
                    return value == JsonToken.VALUE_NUMBER_INT ? BodyKind.OK : BodyKind.MALFORMED;
                }
                if (field.equals("code")) {
                    hasCode = true;
                }
                p.skipChildren();
            }
            return hasCode ? BodyKind.REJECTED : BodyKind.MALFORMED;
        } catch (JacksonException e) {
            return BodyKind.MALFORMED;   // e.g. an HTML page with a 200
        }
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
