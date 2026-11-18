package dev.abu.screener_backend.marketdata.core.ingress;

import dev.abu.screener_backend.config.DisruptorProperties;
import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.OrderbookProperties;
import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlotTable;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlertStore;
import dev.abu.screener_backend.marketdata.core.trade.TradeDetector;
import dev.abu.screener_backend.marketdata.core.trade.TradeThresholds;
import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.SyncStrategyRegistry;
import dev.abu.screener_backend.marketdata.spi.TradeDecoderRegistry;
import dev.abu.screener_backend.marketdata.spi.VenueStrategyBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shard tick, wired end to end: ticker → ring → each shard's handler → the detector. */
class DisruptorShardManagerTest {

    private static final int SHARDS = 2;
    private static final int INSTRUMENTS = 6;

    private final PipelineMetrics metrics = new PipelineMetrics();
    /** {@code instrument id → name of the thread that checked it}, from the detector's idle close. */
    private final Map<Integer, String> visitedBy = new ConcurrentHashMap<>();
    private DisruptorShardManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) manager.shutdown();
    }

    @Test
    @DisplayName("with an idle close configured, ticks reach every shard, and each shard checks only its own ids on its own thread")
    void ticksReachEveryShard() throws InterruptedException {
        manager = start(Map.of(Venue.MEXC_FUTURES, 1_500L));
        assertTrue(manager.tickerRunning());

        long deadline = System.currentTimeMillis() + 5_000;
        while (visitedBy.size() < INSTRUMENTS && System.currentTimeMillis() < deadline) Thread.sleep(10);

        assertEquals(INSTRUMENTS, visitedBy.size(), "every instrument checked: " + visitedBy);
        visitedBy.forEach((id, thread) ->
                assertEquals("disruptor-shard-" + (id & (SHARDS - 1)), thread, "instrument " + id));
        assertEquals(0, manager.processedPerShard()[0] + manager.processedPerShard()[1],
                "ticks are not counted as processed events");
    }

    @Test
    @DisplayName("without an idle close, no ticker runs")
    void noTickerWithoutIdleClose() throws InterruptedException {
        manager = start(Map.of());

        Thread.sleep(100);   // ten tick intervals
        assertFalse(manager.tickerRunning());
        assertTrue(visitedBy.isEmpty(), "no tick was handled");
    }

    private DisruptorShardManager start(Map<Venue, Long> idle) {
        TradeAlertProperties props = new TradeAlertProperties(1, null, null, 10, 10, idle, 10);
        DepthSyncStrategy strategy = new DepthSyncStrategy() {
            @Override
            public BookSyncContext newContext() {
                return new BookSyncContext() { };
            }

            @Override
            public void onEvent(InstrumentSlot slot, MarketEvent event) { }
        };
        InstrumentSlotTable slots = new InstrumentSlotTable(new OrderbookProperties(0.1),
                new SyncStrategyRegistry(List.of(new VenueStrategyBinding(Venue.MEXC_FUTURES, strategy))),
                new TradeDecoderRegistry(List.of()));
        for (int id = 0; id < INSTRUMENTS; id++) {
            slots.allocate(Instrument.of(id, Venue.MEXC_FUTURES, "C" + id + "_USDT", "C" + id, "USDT"));
        }
        slots.publish();

        TradeDetector detector = new TradeDetector(metrics, props, new TradeThresholds(props), new TradeAlertStore(props)) {
            @Override
            public void closeIfIdle(InstrumentSlot slot) {
                visitedBy.putIfAbsent(slot.instrument().id(), Thread.currentThread().getName());
                super.closeIfIdle(slot);
            }
        };
        DisruptorShardManager m = new DisruptorShardManager(new DisruptorProperties(SHARDS, 64), slots, null, null,
                new ExchangesProperties(null), detector, metrics, props);
        m.start();
        return m;
    }
}
