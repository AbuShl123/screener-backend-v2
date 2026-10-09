package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcContractDto;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcSpotExchangeInfo;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcSpotSymbolDto;
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
 * MEXC's inclusion policy and mapping for both venues, driven through {@code contract/detail}- and
 * {@code exchangeInfo}-shaped JSON (rows trimmed from real responses) and stubbed REST clients.
 */
class MexcInstrumentSourceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CONTRACTS = """
            [
              {"symbol": "BTC_USDT",       "baseCoin": "BTC",  "baseCoinName": "BTC", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true,  "contractSize": 0.0001, "settleCoin": "USDT", "conceptPlate": ["mc-trade-zone-mainly", "mc-trade-zone-pow"]},
              {"symbol": "SHIB_USDT",      "baseCoin": "SHIB", "baseCoinName": "SHIB", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true,  "contractSize": 1000},
              {"symbol": "ETH_USDT_DLV",   "baseCoin": "ETH",  "baseCoinName": "ETH", "quoteCoin": "USDT", "futureType": 2, "state": 0, "apiAllowed": true,  "contractSize": 0.01},
              {"symbol": "LUNA_USDT",      "baseCoin": "LUNA", "baseCoinName": "LUNA", "quoteCoin": "USDT", "futureType": 1, "state": 3, "apiAllowed": true,  "contractSize": 1},
              {"symbol": "ZC_USDT",        "baseCoin": "ZC",   "baseCoinName": "ZC", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": false, "contractSize": 1},
              {"symbol": "BTC_USDC",       "baseCoin": "BTC",  "baseCoinName": "BTC", "quoteCoin": "USDC", "futureType": 1, "state": 0, "apiAllowed": true,  "contractSize": 0.0001},
              {"symbol": "NOSTATE_USDT",   "baseCoin": "NOSTATE", "quoteCoin": "USDT", "futureType": 1, "apiAllowed": true, "contractSize": 1},
              {"symbol": "PLTRSTOCK_USDT", "baseCoin": "PLTRSTOCK", "baseCoinName": "PLTRSTOCK", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0.01},
              {"symbol": "XAU_USDT",       "baseCoin": "XAU",  "baseCoinName": "XAU", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true,  "contractSize": 0.001, "conceptPlate": ["mc-trade-zone-metals", "mc-trade-zone-tradfi"]},
              {"symbol": "USOIL_USDT",     "baseCoin": "USOIL", "baseCoinName": "USOIL", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0.1, "conceptPlate": ["mc-trade-zone-OIL", "mc-trade-zone-tradfi"]},
              {"symbol": "NVIDIA_USDT",    "baseCoin": "NVIDIA", "baseCoinName": "NVIDIA", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0.01, "conceptPlate": ["mc-trade-zone-Stock", "mc-trade-zone-tradfi"]}
            ]
            """;

    /** Contracts whose {@code baseCoin} is an internal id, and one with no {@code baseCoinName}. */
    private static final String RENAMED_CONTRACTS = """
            [
              {"symbol": "FILECOIN_USDT",    "baseCoin": "FILECOIN",    "baseCoinName": "FIL",    "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0.1},
              {"symbol": "LONGXIA_USDT",     "baseCoin": "LONGXIA",     "baseCoinName": "龙虾",    "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 1},
              {"symbol": "NONAME_USDT",      "baseCoin": "NONAME",                              "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 1}
            ]
            """;

    private static final String SPOT = """
            {"timezone": "CST", "serverTime": 1791556844300, "symbols": [
              {"symbol": "BTCUSDT",       "status": "1", "baseAsset": "BTC",      "quoteAsset": "USDT", "isSpotTradingAllowed": true, "permissions": ["SPOT"]},
              {"symbol": "SHIBUSDT",      "status": "1", "baseAsset": "SHIB",     "quoteAsset": "USDT", "isSpotTradingAllowed": false},
              {"symbol": "BTCUSDC",       "status": "1", "baseAsset": "BTC",      "quoteAsset": "USDC", "isSpotTradingAllowed": true},
              {"symbol": "LUNAUSDT",      "status": "1", "baseAsset": "LUNA",     "quoteAsset": "USDT", "isSpotTradingAllowed": true},
              {"symbol": "XAUUSDT",       "status": "1", "baseAsset": "XAU",      "quoteAsset": "USDT", "isSpotTradingAllowed": true},
              {"symbol": "PEPEUSDT",      "status": "1", "baseAsset": "PEPE",     "quoteAsset": "USDT", "isSpotTradingAllowed": true},
              {"symbol": "PAUSEDUSDT",    "status": "2", "baseAsset": "BTC",      "quoteAsset": "USDT", "isSpotTradingAllowed": true},
              {"symbol": "NOFLAGUSDT",    "status": "1", "baseAsset": "BTC",      "quoteAsset": "USDT"},
              {"symbol": "FILUSDT",       "status": "1", "baseAsset": "FIL",      "quoteAsset": "USDT", "isSpotTradingAllowed": true},
              {"symbol": "FILECOINUSDT",  "status": "1", "baseAsset": "FILECOIN", "quoteAsset": "USDT", "isSpotTradingAllowed": true},
              {"symbol": "龙虾USDT",       "status": "1", "baseAsset": "龙虾",      "quoteAsset": "USDT", "isSpotTradingAllowed": true},
              {"symbol": "NONAMEUSDT",    "status": "1", "baseAsset": "NONAME",   "quoteAsset": "USDT", "isSpotTradingAllowed": true}
            ]}
            """;

    @Test
    @DisplayName("claims both MEXC venues")
    void venues() {
        assertEquals(Set.of(Venue.MEXC_SPOT, Venue.MEXC_FUTURES), source(Mono.empty(), Mono.empty()).venues());
    }

    // --- Futures -------------------------------------------------------------------------------

    @Test
    @DisplayName("futures: USDT ∧ perpetual ∧ enabled ∧ apiAllowed ∧ not TradFi (tag or STOCK suffix); a missing state is not 'enabled'")
    void futuresPolicy() {
        List<InstrumentCandidate> result = source(spot(SPOT), contracts(CONTRACTS)).fetch().get(Venue.MEXC_FUTURES);

        assertEquals(List.of("BTC_USDT", "SHIB_USDT"), symbols(result));
    }

    @Test
    @DisplayName("futures: BTC_USDT keeps its native symbol, splits into BTC/USDT and carries the contract size")
    void futuresMapping() {
        Map<Venue, List<InstrumentCandidate>> result = source(spot(SPOT), contracts(CONTRACTS)).fetch();

        assertEquals(new InstrumentCandidate("BTC_USDT", "BTC", "USDT", 0.0001), result.get(Venue.MEXC_FUTURES).getFirst());
        assertEquals(1000.0, result.get(Venue.MEXC_FUTURES).get(1).quantityMultiplier());
    }

    @Test
    @DisplayName("futures: a contract with an unusable contract size is skipped, not fatal to the whole refresh")
    void badContractSizeSkipped() {
        String json = """
                [
                  {"symbol": "BTC_USDT", "baseCoin": "BTC", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0.0001},
                  {"symbol": "ZERO_USDT", "baseCoin": "ZERO", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0},
                  {"symbol": "NONE_USDT", "baseCoin": "NONE", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true}
                ]
                """;

        List<InstrumentCandidate> result = source(spot(SPOT), contracts(json)).fetch().get(Venue.MEXC_FUTURES);

        assertEquals(List.of("BTC_USDT"), symbols(result));
    }

    @Test
    @DisplayName("futures: base stays baseCoin even when it is an internal id")
    void futuresBaseIsBaseCoin() {
        List<InstrumentCandidate> result = source(spot(SPOT), contracts(RENAMED_CONTRACTS)).fetch().get(Venue.MEXC_FUTURES);

        assertEquals(new InstrumentCandidate("FILECOIN_USDT", "FILECOIN", "USDT", 0.1), result.getFirst());
    }

    // --- Spot ----------------------------------------------------------------------------------

    @Test
    @DisplayName("spot: USDT ∧ status 1 ∧ isSpotTradingAllowed ∧ base has an eligible futures contract (so TradFi is inherited)")
    void spotPolicy() {
        List<InstrumentCandidate> result = source(spot(SPOT), contracts(CONTRACTS)).fetch().get(Venue.MEXC_SPOT);

        // SHIB: trading not allowed. BTCUSDC: quote. LUNA: contract offline. XAU: TradFi contract.
        // PEPE: no contract. PAUSED: status 2. NOFLAG: isSpotTradingAllowed absent.
        assertEquals(List.of("BTCUSDT"), symbols(result));
        assertEquals(new InstrumentCandidate("BTCUSDT", "BTC", "USDT", 1.0), result.getFirst());
    }

    @Test
    @DisplayName("spot: matched on baseCoinName, not baseCoin; a contract without baseCoinName matches nothing")
    void spotMatchesBaseCoinName() {
        List<InstrumentCandidate> result = source(spot(SPOT), contracts(RENAMED_CONTRACTS)).fetch().get(Venue.MEXC_SPOT);

        assertEquals(List.of("FILUSDT", "龙虾USDT"), symbols(result));
        assertEquals(new InstrumentCandidate("FILUSDT", "FIL", "USDT"), result.getFirst());
    }

    // --- Failure -------------------------------------------------------------------------------

    @Test
    @DisplayName("either REST call failing, or an empty body, throws rather than returning an empty universe")
    void failureThrows() {
        Mono<List<MexcContractDto>> throttled = Mono.error(
                new MexcApiException(Venue.MEXC_FUTURES, 510, "Requests are too frequent"));
        Mono<List<MexcSpotSymbolDto>> blocked = Mono.error(
                new ExchangeApiException(Venue.MEXC_SPOT, HttpStatus.FORBIDDEN, HttpHeaders.EMPTY, "Access Denied"));

        assertThrows(MexcApiException.class, () -> source(spot(SPOT), throttled).fetch());
        assertThrows(ExchangeApiException.class, () -> source(blocked, contracts(CONTRACTS)).fetch());
        assertThrows(IllegalStateException.class, () -> source(spot(SPOT), Mono.empty()).fetch());
        assertThrows(IllegalStateException.class, () -> source(Mono.empty(), contracts(CONTRACTS)).fetch());
    }

    // ---------------------------------------------------------------- fixtures

    private static List<String> symbols(List<InstrumentCandidate> candidates) {
        return candidates.stream().map(InstrumentCandidate::nativeSymbol).toList();
    }

    private static MexcInstrumentSource source(Mono<List<MexcSpotSymbolDto>> spot, Mono<List<MexcContractDto>> futures) {
        return new MexcInstrumentSource(new StubSpotClient(spot), new StubFuturesClient(futures));
    }

    private static Mono<List<MexcContractDto>> contracts(String json) {
        return Mono.just(JSON.readValue(json, new TypeReference<List<MexcContractDto>>() {}));
    }

    private static Mono<List<MexcSpotSymbolDto>> spot(String json) {
        return Mono.just(JSON.readValue(json, MexcSpotExchangeInfo.class).symbols());
    }

    /** Serves a canned contract list without touching a real WebClient. */
    private static final class StubFuturesClient extends MexcFuturesRestClient {
        private final Mono<List<MexcContractDto>> response;

        StubFuturesClient(Mono<List<MexcContractDto>> response) {
            super(null);
            this.response = response;
        }

        @Override
        public Mono<List<MexcContractDto>> contractDetail() {
            return response;
        }
    }

    /** Serves a canned spot symbol list without touching a real WebClient. */
    private static final class StubSpotClient extends MexcSpotRestClient {
        private final Mono<List<MexcSpotSymbolDto>> response;

        StubSpotClient(Mono<List<MexcSpotSymbolDto>> response) {
            super(null);
            this.response = response;
        }

        @Override
        public Mono<List<MexcSpotSymbolDto>> exchangeInfo() {
            return response;
        }
    }
}
