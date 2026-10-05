package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceInstrumentSource;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceRestClient;
import dev.abu.screener_backend.marketdata.adapter.binance.dto.ExchangeInfoResponse;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeApiException;
import dev.abu.screener_backend.marketdata.spi.InstrumentCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Binance's inclusion policy, driven through real {@code exchangeInfo}-shaped JSON and a stubbed
 * {@link BinanceRestClient}.
 */
class BinanceInstrumentSourceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String FUTURES = """
            {"symbols": [
              {"symbol": "BTCUSDT",        "baseAsset": "BTC",  "quoteAsset": "USDT", "status": "TRADING", "contractType": "PERPETUAL"},
              {"symbol": "SOLUSDT",        "baseAsset": "SOL",  "quoteAsset": "USDT", "status": "TRADING", "contractType": "PERPETUAL"},
              {"symbol": "ETHUSDT_250926", "baseAsset": "ETH",  "quoteAsset": "USDT", "status": "TRADING", "contractType": "CURRENT_QUARTER"},
              {"symbol": "XRPUSDT",        "baseAsset": "XRP",  "quoteAsset": "USDT", "status": "SETTLING", "contractType": "PERPETUAL"},
              {"symbol": "ETHBTC",         "baseAsset": "ETH",  "quoteAsset": "BTC",  "status": "TRADING", "contractType": "PERPETUAL"}
            ]}
            """;

    private static final String SPOT = """
            {"symbols": [
              {"symbol": "BTCUSDT",  "baseAsset": "BTC",  "quoteAsset": "USDT", "status": "TRADING"},
              {"symbol": "SOLUSDT",  "baseAsset": "SOL",  "quoteAsset": "USDT", "status": "BREAK"},
              {"symbol": "DOGEUSDT", "baseAsset": "DOGE", "quoteAsset": "USDT", "status": "TRADING"},
              {"symbol": "XRPUSDT",  "baseAsset": "XRP",  "quoteAsset": "USDT", "status": "TRADING"},
              {"symbol": "ETHBTC",   "baseAsset": "ETH",  "quoteAsset": "BTC",  "status": "TRADING"}
            ]}
            """;

    @Test
    @DisplayName("claims exactly the two Binance venues")
    void venues() {
        BinanceInstrumentSource source = source(Mono.empty(), Mono.empty());

        assertEquals(Set.of(Venue.BINANCE_SPOT, Venue.BINANCE_FUTURES), source.venues());
    }

    @Test
    @DisplayName("futures: TRADING ∧ PERPETUAL ∧ quote USDT")
    void futuresPolicy() {
        Map<Venue, List<InstrumentCandidate>> result = source(parse(SPOT), parse(FUTURES)).fetch();

        assertEquals(List.of("BTCUSDT", "SOLUSDT"), symbols(result.get(Venue.BINANCE_FUTURES)));
    }

    @Test
    @DisplayName("spot: the futures intersection, plus the spot filters")
    void spotPolicy() {
        Map<Venue, List<InstrumentCandidate>> result = source(parse(SPOT), parse(FUTURES)).fetch();

        // SOLUSDT is in futures but not TRADING on spot; DOGEUSDT/XRPUSDT have no eligible future.
        assertEquals(List.of("BTCUSDT"), symbols(result.get(Venue.BINANCE_SPOT)));
    }

    @Test
    @DisplayName("candidates carry base and quote assets")
    void candidateShape() {
        Map<Venue, List<InstrumentCandidate>> result = source(parse(SPOT), parse(FUTURES)).fetch();

        assertEquals(new InstrumentCandidate("BTCUSDT", "BTC", "USDT"), result.get(Venue.BINANCE_SPOT).getFirst());
    }

    @Test
    @DisplayName("a failing REST call throws rather than returning an empty universe")
    void restFailureThrows() {
        Mono<ExchangeInfoResponse> failing = Mono.error(
                new ExchangeApiException(Venue.BINANCE_SPOT, HttpStatus.SERVICE_UNAVAILABLE, "down"));

        assertThrows(RuntimeException.class, () -> source(parse(SPOT), failing).fetch());
        assertThrows(RuntimeException.class, () -> source(failing, parse(FUTURES)).fetch());
    }

    @Test
    @DisplayName("an empty body or a missing symbols array throws")
    void malformedResponseThrows() {
        assertThrows(IllegalStateException.class, () -> source(Mono.empty(), parse(FUTURES)).fetch());
        assertThrows(IllegalStateException.class, () -> source(parse("{}"), parse(FUTURES)).fetch());
    }

    // ---------------------------------------------------------------- fixtures

    private static BinanceInstrumentSource source(Mono<ExchangeInfoResponse> spot,
                                                  Mono<ExchangeInfoResponse> futures) {
        return new BinanceInstrumentSource(
                new StubRestClient(Venue.BINANCE_SPOT, spot),
                new StubRestClient(Venue.BINANCE_FUTURES, futures));
    }

    private static Mono<ExchangeInfoResponse> parse(String json) {
        return Mono.just(JSON.readValue(json, ExchangeInfoResponse.class));
    }

    private static List<String> symbols(List<InstrumentCandidate> candidates) {
        return candidates.stream().map(InstrumentCandidate::nativeSymbol).sorted().toList();
    }

    /** Serves a canned {@code exchangeInfo} response without touching a real WebClient. */
    private static final class StubRestClient extends BinanceRestClient {
        private final Mono<ExchangeInfoResponse> response;

        StubRestClient(Venue venue, Mono<ExchangeInfoResponse> response) {
            super(venue, null, null);
            this.response = response;
        }

        @Override
        public Mono<ExchangeInfoResponse> exchangeInfo() {
            return response;
        }
    }
}
