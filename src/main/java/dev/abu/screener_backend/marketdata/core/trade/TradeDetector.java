package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import org.springframework.stereotype.Component;

/**
 * The core {@link TradeSink}: every decoded trade of every venue lands here, on its instrument's
 * shard consumer thread.
 *
 * <p>One bean shared by all shards. It holds no per-instrument state of its own: what detection
 * needs per instrument lives in the {@link InstrumentSlot}, which only the instrument's shard thread
 * touches, so no locks are needed. Its own fields are shared, thread-safe services only.
 *
 * <p>Today it only counts decoded trades. Threshold detection and sweep coalescing follow
 * ({@code .claude/plans/trade-detection-plan.md}, slices 2 and 3).
 */
@Component
public class TradeDetector implements TradeSink {

    private final PipelineMetrics metrics;

    public TradeDetector(PipelineMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onTrade(InstrumentSlot slot, double price, double qty, boolean aggressorBuy,
                        long tradeTimeMs, long firstTradeId, long lastTradeId) {
        metrics.recordTradeDecoded(slot.instrument().venue());
    }
}
