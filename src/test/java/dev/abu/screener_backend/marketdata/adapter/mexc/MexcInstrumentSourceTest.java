package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcApiException;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcFuturesRestClient;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcInstrumentSource;
import dev.abu.screener_backend.marketdata.adapter.mexc.dto.MexcContractDto;
import dev.abu.screener_backend.marketdata.spi.InstrumentCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MEXC's inclusion policy and mapping, driven through {@code contract/detail}-shaped JSON (rows
 * trimmed from a real response) and a stubbed {@link MexcFuturesRestClient}.
 */
class MexcInstrumentSourceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CONTRACTS = """
            [
              {"symbol": "BTC_USDT",       "baseCoin": "BTC",  "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true,  "contractSize": 0.0001, "settleCoin": "USDT", "conceptPlate": ["mc-trade-zone-mainly", "mc-trade-zone-pow"]},
              {"symbol": "SHIB_USDT",      "baseCoin": "SHIB", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true,  "contractSize": 1000},
              {"symbol": "ETH_USDT_DLV",   "baseCoin": "ETH",  "quoteCoin": "USDT", "futureType": 2, "state": 0, "apiAllowed": true,  "contractSize": 0.01},
              {"symbol": "LUNA_USDT",      "baseCoin": "LUNA", "quoteCoin": "USDT", "futureType": 1, "state": 3, "apiAllowed": true,  "contractSize": 1},
              {"symbol": "ZC_USDT",        "baseCoin": "ZC",   "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": false, "contractSize": 1},
              {"symbol": "BTC_USDC",       "baseCoin": "BTC",  "quoteCoin": "USDC", "futureType": 1, "state": 0, "apiAllowed": true,  "contractSize": 0.0001},
              {"symbol": "NOSTATE_USDT",   "baseCoin": "NOSTATE", "quoteCoin": "USDT", "futureType": 1, "apiAllowed": true, "contractSize": 1},
              {"symbol": "PLTRSTOCK_USDT", "baseCoin": "PLTRSTOCK", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0.01},
              {"symbol": "XAU_USDT",       "baseCoin": "XAU",  "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true,  "contractSize": 0.001, "conceptPlate": ["mc-trade-zone-metals", "mc-trade-zone-tradfi"]},
              {"symbol": "USOIL_USDT",     "baseCoin": "USOIL", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0.1, "conceptPlate": ["mc-trade-zone-OIL", "mc-trade-zone-tradfi"]},
              {"symbol": "NVIDIA_USDT",    "baseCoin": "NVIDIA", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0.01, "conceptPlate": ["mc-trade-zone-Stock", "mc-trade-zone-tradfi"]}
            ]
            """;

    @Test
    @DisplayName("claims exactly MEXC futures")
    void venues() {
        assertEquals(Set.of(Venue.MEXC_FUTURES), source(Mono.empty()).venues());
    }

    @Test
    @DisplayName("USDT ∧ perpetual ∧ enabled ∧ apiAllowed ∧ not TradFi (tag or STOCK suffix); a missing state is not 'enabled'")
    void policy() {
        List<InstrumentCandidate> result = source(parse(CONTRACTS)).fetch().get(Venue.MEXC_FUTURES);

        assertEquals(List.of("BTC_USDT", "SHIB_USDT"), result.stream().map(InstrumentCandidate::nativeSymbol).toList());
    }

    @Test
    @DisplayName("BTC_USDT keeps its native symbol, splits into BTC/USDT and carries the contract size")
    void mapping() {
        Map<Venue, List<InstrumentCandidate>> result = source(parse(CONTRACTS)).fetch();

        assertEquals(new InstrumentCandidate("BTC_USDT", "BTC", "USDT", 0.0001), result.get(Venue.MEXC_FUTURES).getFirst());
        assertEquals(1000.0, result.get(Venue.MEXC_FUTURES).get(1).quantityMultiplier());
    }

    @Test
    @DisplayName("a contract with an unusable contract size is skipped, not fatal to the whole refresh")
    void badContractSizeSkipped() {
        String json = """
                [
                  {"symbol": "BTC_USDT", "baseCoin": "BTC", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0.0001},
                  {"symbol": "ZERO_USDT", "baseCoin": "ZERO", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true, "contractSize": 0},
                  {"symbol": "NONE_USDT", "baseCoin": "NONE", "quoteCoin": "USDT", "futureType": 1, "state": 0, "apiAllowed": true}
                ]
                """;

        List<InstrumentCandidate> result = source(parse(json)).fetch().get(Venue.MEXC_FUTURES);

        assertEquals(List.of("BTC_USDT"), result.stream().map(InstrumentCandidate::nativeSymbol).toList());
    }

    @Test
    @DisplayName("a failing REST call or an empty body throws rather than returning an empty universe")
    void failureThrows() {
        Mono<List<MexcContractDto>> throttled = Mono.error(
                new MexcApiException(Venue.MEXC_FUTURES, 510, "Requests are too frequent"));

        assertThrows(MexcApiException.class, () -> source(throttled).fetch());
        assertThrows(IllegalStateException.class, () -> source(Mono.empty()).fetch());
    }

    // ---------------------------------------------------------------- fixtures

    private static MexcInstrumentSource source(Mono<List<MexcContractDto>> response) {
        return new MexcInstrumentSource(new StubRestClient(response));
    }

    private static Mono<List<MexcContractDto>> parse(String json) {
        return Mono.just(JSON.readValue(json, new TypeReference<List<MexcContractDto>>() {}));
    }

    /** Serves a canned contract list without touching a real WebClient. */
    private static final class StubRestClient extends MexcFuturesRestClient {
        private final Mono<List<MexcContractDto>> response;

        StubRestClient(Mono<List<MexcContractDto>> response) {
            super(null);
            this.response = response;
        }

        @Override
        public Mono<List<MexcContractDto>> contractDetail() {
            return response;
        }
    }
}
