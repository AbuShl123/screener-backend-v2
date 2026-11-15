package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.bybit.dto.BybitInstrumentDto;
import dev.abu.screener_backend.marketdata.adapter.bybit.dto.BybitInstrumentsPage;
import dev.abu.screener_backend.marketdata.adapter.bybit.dto.BybitResponse;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Typed REST client for one Bybit venue's public v5 market API. One instance per venue, each with
 * its own {@link WebClient}; the venue picks the {@code category} query parameter ({@code spot} or
 * {@code linear}). Unlike MEXC, both categories are the same API, so one class serves both, as
 * {@code BinanceRestClient} does.
 *
 * <p>All methods return cold {@link Mono} publishers. Failures travel the error channel, logged at
 * {@code WARN} first, in one of two shapes:
 * <ul>
 *   <li>{@link ExchangeApiException}: a non-2xx status, e.g. the HTTP 403 of an IP ban or a
 *       geo-block;</li>
 *   <li>{@link BybitApiException}: a 2xx whose {@code retCode} is not {@code 0}. Bybit answers a
 *       rate-limited request ({@code 10006} / {@code 10018}) with HTTP 200, so a 2xx alone never
 *       means success (contracts §3.1).</li>
 * </ul>
 * {@link #depth} is the exception to the second: it returns the raw body, envelope included, for
 * the snapshot fetcher to classify by {@code retCode}.
 */
@Slf4j
public class BybitRestClient {

    /** Linear honours up to 1000; spot ignores {@code limit} and always returns everything. */
    static final int INSTRUMENTS_PAGE_LIMIT = 1000;
    /**
     * A runaway-pagination guard: the whole linear universe (~930 instruments, 2026-11) fits in one
     * page of 1000, so ten pages would be a tenfold growth or a cursor that never ends. Failing beats
     * returning a truncated universe, which core would read as a mass delisting.
     */
    static final int MAX_INSTRUMENT_PAGES = 10;

    private static final String INSTRUMENTS_PATH = "/v5/market/instruments-info?category={category}&limit={limit}";
    private static final String INSTRUMENTS_NEXT_PAGE_PATH = INSTRUMENTS_PATH + "&cursor={cursor}";
    private static final String DEPTH_PATH = "/v5/market/orderbook?category={category}&symbol={symbol}&limit={limit}";
    private static final ParameterizedTypeReference<BybitResponse<BybitInstrumentsPage>> INSTRUMENTS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final Venue venue;
    private final String category;
    private final WebClient webClient;

    /** @throws IllegalArgumentException if {@code venue} is not a Bybit venue */
    public BybitRestClient(Venue venue, WebClient webClient) {
        if (venue.exchange() != Exchange.BYBIT) {
            throw new IllegalArgumentException("Not a Bybit venue: " + venue);
        }
        this.venue = venue;
        this.category = category(venue);
        this.webClient = webClient;
    }

    /** The v5 {@code category} for a Bybit venue: spot is {@code spot}, futures (USDT perpetuals) {@code linear}. */
    static String category(Venue venue) {
        return switch (venue.market()) {
            case SPOT -> "spot";
            case FUTURES -> "linear";
        };
    }

    public Venue venue() {
        return venue;
    }

    /**
     * Every instrument this venue's category lists, unfiltered, across every page. Without a
     * {@code status} parameter Bybit returns {@code Trading} instruments only (contracts §3.2).
     *
     * <p>One pagination path serves both categories: it follows {@code nextPageCursor} until it is
     * empty or absent. Linear returns a cursor ({@code ""} on the last page); spot never sends the
     * field, so it always stops after the first page, which holds the whole universe.
     *
     * @throws IllegalStateException (in the {@code Mono}) if a page has no instrument list, or the
     *                               cursor runs past {@link #MAX_INSTRUMENT_PAGES} pages
     */
    public Mono<List<BybitInstrumentDto>> instrumentsInfo() {
        Mono<List<BybitInstrumentDto>> all = instrumentsPage(null)
                .expand(page -> hasNextPage(page) ? instrumentsPage(page.nextPageCursor()) : Mono.empty())
                .index()
                .doOnNext(indexed -> {
                    if (indexed.getT1() >= MAX_INSTRUMENT_PAGES) {
                        throw new IllegalStateException(venue + " instruments-info did not end within "
                                + MAX_INSTRUMENT_PAGES + " pages");
                    }
                })
                .concatMapIterable(indexed -> indexed.getT2().list())
                .collectList();
        return logErrors(all, INSTRUMENTS_PATH);
    }

    /**
     * One page; {@code cursor} is {@code null} for the first.
     *
     * <p>Bybit sends the cursor already percent-encoded ({@code first%3D0GUSDT%26last%3DAXSUSDT}) and
     * expects it back as-is. WebClient encodes URI variables, so the cursor is decoded once here and
     * goes out exactly as Bybit sent it, rather than double-encoded. (Bybit happens to accept both,
     * but that is not documented.)
     */
    private Mono<BybitInstrumentsPage> instrumentsPage(String cursor) {
        WebClient.ResponseSpec response = cursor == null
                ? retrieve(INSTRUMENTS_PATH, category, INSTRUMENTS_PAGE_LIMIT)
                : retrieve(INSTRUMENTS_NEXT_PAGE_PATH, category, INSTRUMENTS_PAGE_LIMIT,
                        URLDecoder.decode(cursor, StandardCharsets.UTF_8));
        return response.bodyToMono(INSTRUMENTS_TYPE)
                .map(this::unwrap)
                .map(page -> {
                    if (page.list() == null) {
                        throw new IllegalStateException(venue + " instruments-info response has no list");
                    }
                    return page;
                });
    }

    private static boolean hasNextPage(BybitInstrumentsPage page) {
        return page.nextPageCursor() != null && !page.nextPageCursor().isEmpty();
    }

    /**
     * One symbol's order book, as the raw body: not unwrapped through {@link BybitResponse}, since
     * the sync strategy stream-parses it and the snapshot fetcher reads its {@code retCode} first. So
     * a rate-limited request ({@code 200}, {@code retCode 10006}) arrives here as a value, not an
     * error. A non-2xx status is an {@link ExchangeApiException}.
     *
     * <p>The symbol is a URI variable, never pre-encoded into the path, as in {@code BinanceRestClient}.
     */
    public Mono<String> depth(String symbol, int limit) {
        return logErrors(retrieve(DEPTH_PATH, category, symbol, limit).bodyToMono(String.class),
                DEPTH_PATH + " " + symbol);
    }

    private <T> T unwrap(BybitResponse<T> response) {
        if (response.retCode() == null) {
            throw new IllegalStateException(venue + " response has no retCode");
        }
        if (response.retCode() != 0) {
            throw new BybitApiException(venue, response.retCode(), response.retMsg());
        }
        if (response.result() == null) {
            throw new IllegalStateException(venue + " response reports success but carries no result");
        }
        return response.result();
    }

    private WebClient.ResponseSpec retrieve(String uriTemplate, Object... uriVariables) {
        return webClient.get()
                .uri(uriTemplate, uriVariables)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> ExchangeApiException.from(venue, response));
    }

    private <T> Mono<T> logErrors(Mono<T> call, String path) {
        return call.doOnError(ex -> log.warn("[{}] REST call failed [{}]: {}", venue, path,
                Objects.toString(ex.getMessage(), ex.getClass().getSimpleName())));
    }
}
