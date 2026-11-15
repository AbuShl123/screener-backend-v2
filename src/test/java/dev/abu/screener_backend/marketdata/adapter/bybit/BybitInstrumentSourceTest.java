package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.bybit.dto.BybitInstrumentDto;
import dev.abu.screener_backend.marketdata.adapter.bybit.dto.BybitInstrumentsPage;
import dev.abu.screener_backend.marketdata.adapter.bybit.dto.BybitResponse;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeApiException;
import dev.abu.screener_backend.marketdata.spi.InstrumentCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Bybit's inclusion policy and mapping for both venues, driven through the captured
 * {@code instruments-info} payloads ({@code src/test/resources/bybit/}), inline rows for the cases
 * those do not cover, and stubbed REST clients.
 */
class BybitInstrumentSourceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Linear rows the captured payload lacks: TradFi classes, Innovation Zone, ST, non-Trading. */
    private static final String LINEAR = """
            [
              {"symbol": "BTCUSDT",      "contractType": "LinearPerpetual", "status": "Trading",   "baseCoin": "BTC",     "quoteCoin": "USDT", "symbolType": ""},
              {"symbol": "AAPLUSDT",     "contractType": "LinearPerpetual", "status": "Trading",   "baseCoin": "AAPL",    "quoteCoin": "USDT", "symbolType": "stock"},
              {"symbol": "IBITUSDT",     "contractType": "LinearPerpetual", "status": "Trading",   "baseCoin": "IBIT",    "quoteCoin": "USDT", "symbolType": "ETF"},
              {"symbol": "XAUUSDT",      "contractType": "LinearPerpetual", "status": "Trading",   "baseCoin": "XAU",     "quoteCoin": "USDT", "symbolType": "commodity"},
              {"symbol": "EURUSDUSDT",   "contractType": "LinearPerpetual", "status": "Trading",   "baseCoin": "EURUSD",  "quoteCoin": "USDT", "symbolType": "forex"},
              {"symbol": "AVNTUSDT",     "contractType": "LinearPerpetual", "status": "Trading",   "baseCoin": "AVNT",    "quoteCoin": "USDT", "symbolType": "innovation"},
              {"symbol": "CTCUSDT",      "contractType": "LinearPerpetual", "status": "Trading",   "baseCoin": "CTC",     "quoteCoin": "USDT", "symbolType": "", "tags": ["ST"]},
              {"symbol": "NEWUSDT",      "contractType": "LinearPerpetual", "status": "PreLaunch", "baseCoin": "NEW",     "quoteCoin": "USDT", "symbolType": ""},
              {"symbol": "NOTYPEUSDT",   "contractType": "LinearPerpetual", "status": "Trading",   "baseCoin": "NOTYPE",  "quoteCoin": "USDT"},
              {"symbol": "NOBASEUSDT",   "contractType": "LinearPerpetual", "status": "Trading",                          "quoteCoin": "USDT", "symbolType": ""}
            ]
            """;

    private static final String SPOT = """
            [
              {"symbol": "BTCUSDT",    "status": "Trading", "baseCoin": "BTC",    "quoteCoin": "USDT", "stTag": "0", "innovation": "0", "symbolType": ""},
              {"symbol": "AAPLUSDT",   "status": "Trading", "baseCoin": "AAPL",   "quoteCoin": "USDT", "stTag": "0", "innovation": "0", "symbolType": ""},
              {"symbol": "AAPLXUSDT",  "status": "Trading", "baseCoin": "AAPLX",  "quoteCoin": "USDT", "stTag": "0", "innovation": "0", "symbolType": "xstocks"},
              {"symbol": "AVNTUSDT",   "status": "Trading", "baseCoin": "AVNT",   "quoteCoin": "USDT", "stTag": "0", "innovation": "1", "symbolType": ""},
              {"symbol": "CTCUSDT",    "status": "Trading", "baseCoin": "CTC",    "quoteCoin": "USDT", "stTag": "1", "innovation": "0", "symbolType": ""},
              {"symbol": "NEWUSDT",    "status": "Trading", "baseCoin": "NEW",    "quoteCoin": "USDT", "stTag": "0", "innovation": "0", "symbolType": ""},
              {"symbol": "NOTYPEUSDT", "status": "Trading", "baseCoin": "NOTYPE", "quoteCoin": "USDT"}
            ]
            """;

    @Test
    @DisplayName("claims both Bybit venues")
    void venues() {
        assertEquals(Set.of(Venue.BYBIT_SPOT, Venue.BYBIT_FUTURES), source(Mono.empty(), Mono.empty()).venues());
    }

    @Test
    @DisplayName("rejects clients bound to the wrong venues")
    void wrongClients() {
        assertThrows(IllegalArgumentException.class, () -> new BybitInstrumentSource(
                new StubClient(Venue.BYBIT_FUTURES, Mono.empty()), new StubClient(Venue.BYBIT_SPOT, Mono.empty())));
    }

    // --- Captured payloads ---------------------------------------------------------------------

    @Test
    @DisplayName("captured payloads: futures drop USDC (BTCPERP) and dated (BTCUSDT-04DEC26); spot drops non-USDT, ST and pairs without a perpetual")
    void capturedPayloads() {
        Map<Venue, List<InstrumentCandidate>> result =
                source(captured("instruments-info-spot.json"), captured("instruments-info-linear.json")).fetch();

        assertEquals(List.of("1000000BABYDOGEUSDT", "ARBUSDT", "BTCUSDT", "ETHUSDT"), symbols(result.get(Venue.BYBIT_FUTURES)));
        // ETHBTC, BTCUSDC: quote. CTCUSDT: Special Treatment (and no perpetual in this capture).
        assertEquals(List.of("BTCUSDT", "ETHUSDT", "ARBUSDT"), symbols(result.get(Venue.BYBIT_SPOT)));
    }

    @Test
    @DisplayName("mapping: native symbol, base and quote straight from the row, multiplier 1; the linear base keeps its multiplier prefix")
    void mapping() {
        Map<Venue, List<InstrumentCandidate>> result =
                source(captured("instruments-info-spot.json"), captured("instruments-info-linear.json")).fetch();

        assertEquals(new InstrumentCandidate("1000000BABYDOGEUSDT", "1000000BABYDOGE", "USDT", 1.0),
                result.get(Venue.BYBIT_FUTURES).getFirst());
        assertEquals(new InstrumentCandidate("BTCUSDT", "BTC", "USDT", 1.0), result.get(Venue.BYBIT_SPOT).getFirst());
    }

    // --- Futures -------------------------------------------------------------------------------

    @Test
    @DisplayName("futures: TradFi (stock, ETF, commodity, forex) is excluded; innovation, ST and an absent symbolType are kept; non-Trading is dropped")
    void futuresPolicy() {
        List<InstrumentCandidate> result = source(rows(SPOT), rows(LINEAR)).fetch().get(Venue.BYBIT_FUTURES);

        // NOBASEUSDT: eligible, but no baseCoin, so skipped rather than fatal.
        assertEquals(List.of("BTCUSDT", "AVNTUSDT", "CTCUSDT", "NOTYPEUSDT"), symbols(result));
    }

    // --- Spot ----------------------------------------------------------------------------------

    @Test
    @DisplayName("spot: needs an eligible perpetual of the same symbol (so inherits the TradFi filter) and no Special Treatment tag")
    void spotPolicy() {
        List<InstrumentCandidate> result = source(rows(SPOT), rows(LINEAR)).fetch().get(Venue.BYBIT_SPOT);

        // AAPLUSDT: its perpetual is TradFi. AAPLXUSDT: no perpetual. CTCUSDT: stTag 1, though its
        // perpetual is kept. NEWUSDT: perpetual not Trading.
        assertEquals(List.of("BTCUSDT", "AVNTUSDT", "NOTYPEUSDT"), symbols(result));
    }

    // --- Failure -------------------------------------------------------------------------------

    @Test
    @DisplayName("either REST call failing, or an empty body, throws rather than returning an empty universe")
    void failureThrows() {
        Mono<List<BybitInstrumentDto>> rateLimited = Mono.error(
                new BybitApiException(Venue.BYBIT_FUTURES, 10006, "Too many visits!"));
        Mono<List<BybitInstrumentDto>> banned = Mono.error(
                new ExchangeApiException(Venue.BYBIT_SPOT, HttpStatus.FORBIDDEN, HttpHeaders.EMPTY, "access too frequent"));

        assertThrows(BybitApiException.class, () -> source(rows(SPOT), rateLimited).fetch());
        assertThrows(ExchangeApiException.class, () -> source(banned, rows(LINEAR)).fetch());
        assertThrows(IllegalStateException.class, () -> source(rows(SPOT), Mono.empty()).fetch());
        assertThrows(IllegalStateException.class, () -> source(Mono.empty(), rows(LINEAR)).fetch());
    }

    // ---------------------------------------------------------------- fixtures

    private static List<String> symbols(List<InstrumentCandidate> candidates) {
        return candidates.stream().map(InstrumentCandidate::nativeSymbol).toList();
    }

    private static BybitInstrumentSource source(Mono<List<BybitInstrumentDto>> spot, Mono<List<BybitInstrumentDto>> linear) {
        return new BybitInstrumentSource(new StubClient(Venue.BYBIT_SPOT, spot), new StubClient(Venue.BYBIT_FUTURES, linear));
    }

    private static Mono<List<BybitInstrumentDto>> rows(String json) {
        return Mono.just(JSON.readValue(json, new TypeReference<List<BybitInstrumentDto>>() {}));
    }

    private static Mono<List<BybitInstrumentDto>> captured(String fixture) {
        BybitResponse<BybitInstrumentsPage> response = JSON.readValue(BybitRestClientTest.fixture(fixture),
                new TypeReference<BybitResponse<BybitInstrumentsPage>>() {});
        return Mono.just(response.result().list());
    }

    /** Serves a canned instrument list without touching a real WebClient. */
    private static final class StubClient extends BybitRestClient {
        private final Mono<List<BybitInstrumentDto>> response;

        StubClient(Venue venue, Mono<List<BybitInstrumentDto>> response) {
            super(venue, null);
            this.response = response;
        }

        @Override
        public Mono<List<BybitInstrumentDto>> instrumentsInfo() {
            return response;
        }
    }
}
