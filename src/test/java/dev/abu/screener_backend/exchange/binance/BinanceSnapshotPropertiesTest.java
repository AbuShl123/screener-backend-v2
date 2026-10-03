package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Market;
import dev.abu.screener_backend.exchange.binance.BinanceSnapshotProperties.MarketSnapshot;
import dev.abu.screener_backend.exchange.binance.BinanceSnapshotProperties.VenueBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinanceSnapshotPropertiesTest {

    private static final Duration BAN = Duration.ofMinutes(2);

    private static BinanceSnapshotProperties bind(Map<String, String> yaml) {
        return new Binder(new MapConfigurationPropertySource(yaml))
                .bind("screener.exchanges.binance", BinanceSnapshotProperties.class)
                .get();
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 500, 1000})
    @DisplayName("depth-limit 100 / 500 / 1000 bind from the venue block, next to core's keys")
    void validDepthLimitsBind(int depthLimit) {
        BinanceSnapshotProperties props = bind(Map.of(
                "screener.exchanges.binance.enabled", "true",                       // sibling keys ignored
                "screener.exchanges.binance.discovery.quote-asset", "USDT",
                "screener.exchanges.binance.snapshot-queue.max-batch-size", "10",
                "screener.exchanges.binance.venues.SPOT.rest.base-url", "https://x", // core's venue keys ignored
                "screener.exchanges.binance.venues.SPOT.stream-url", "wss://x",
                "screener.exchanges.binance.venues.SPOT.snapshot.depth-limit", String.valueOf(depthLimit),
                "screener.exchanges.binance.venues.SPOT.snapshot.weight-limit-per-minute", "6000",
                "screener.exchanges.binance.venues.SPOT.snapshot.weight-reserve", "200",
                "screener.exchanges.binance.venues.SPOT.snapshot.ban-fallback", "PT2M"));

        MarketSnapshot spot = props.forMarket(Market.SPOT);
        assertEquals(depthLimit, spot.depthLimit());
        assertEquals(5800, spot.weightBudget());
        assertEquals(BAN, spot.banFallback());
    }

    @Test
    @DisplayName("a venue block with only core keys binds, and forMarket reports the missing snapshot")
    void venueWithoutSnapshotBlock() {
        BinanceSnapshotProperties props = bind(Map.of(
                "screener.exchanges.binance.venues.SPOT.snapshot.depth-limit", "1000",
                "screener.exchanges.binance.venues.SPOT.snapshot.weight-limit-per-minute", "6000",
                "screener.exchanges.binance.venues.SPOT.snapshot.weight-reserve", "200",
                "screener.exchanges.binance.venues.SPOT.snapshot.ban-fallback", "PT2M",
                "screener.exchanges.binance.venues.FUTURES.stream-url", "wss://x"));

        assertEquals(1000, props.forMarket(Market.SPOT).depthLimit());
        assertThrows(IllegalStateException.class, () -> props.forMarket(Market.FUTURES));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 800, 1001, 5000})
    @DisplayName("any other depth-limit fails startup — futures would reject it at runtime")
    void otherDepthLimitsFail(int depthLimit) {
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(depthLimit, 6000, 200, BAN));
    }

    @Test
    @DisplayName("an invalid depth-limit in YAML fails the bind")
    void invalidDepthLimitFailsBind() {
        assertThrows(BindException.class, () -> bind(Map.of(
                "screener.exchanges.binance.venues.FUTURES.snapshot.depth-limit", "800",
                "screener.exchanges.binance.venues.FUTURES.snapshot.weight-limit-per-minute", "2400",
                "screener.exchanges.binance.venues.FUTURES.snapshot.weight-reserve", "200",
                "screener.exchanges.binance.venues.FUTURES.snapshot.ban-fallback", "PT2M")));
    }

    @Test
    @DisplayName("weight-reserve must be in [0, weight-limit-per-minute)")
    void reserveBounds() {
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(1000, 6000, -1, BAN));
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(1000, 6000, 6000, BAN));
        new MarketSnapshot(1000, 6000, 0, BAN);
    }

    @Test
    @DisplayName("ban-fallback must be positive")
    void banFallbackPositive() {
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(1000, 6000, 200, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(1000, 6000, 200, null));
    }

    @Test
    @DisplayName("the budget must afford one request on its own market")
    void budgetAffordsOneRequest() {
        // 40 left: under one spot request at limit=1000 (50), over one futures request (20).
        MarketSnapshot tight = new MarketSnapshot(1000, 100, 60, BAN);

        assertThrows(IllegalArgumentException.class,
                () -> new BinanceSnapshotProperties(Map.of(Market.SPOT, new VenueBlock(tight))));
        new BinanceSnapshotProperties(Map.of(Market.FUTURES, new VenueBlock(tight)));
    }

    @Test
    @DisplayName("the cost table returns the empirically confirmed weights for both markets")
    void costTable() {
        assertEquals(5, weight(Market.SPOT, 100));
        assertEquals(25, weight(Market.SPOT, 500));
        assertEquals(50, weight(Market.SPOT, 1000));
        assertEquals(5, weight(Market.FUTURES, 100));
        assertEquals(10, weight(Market.FUTURES, 500));
        assertEquals(20, weight(Market.FUTURES, 1000));
    }

    @Test
    @DisplayName("a market with no block fails loudly")
    void missingMarketThrows() {
        assertThrows(IllegalStateException.class, () -> new BinanceSnapshotProperties(null).forMarket(Market.SPOT));
    }

    private static int weight(Market market, int depthLimit) {
        return new MarketSnapshot(depthLimit, 6000, 0, BAN).weightPerRequest(market);
    }
}
