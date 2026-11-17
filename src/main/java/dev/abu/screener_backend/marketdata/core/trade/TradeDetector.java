package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The core {@link TradeSink}: every decoded trade of every venue lands here, on its instrument's
 * shard consumer thread.
 *
 * <p>One bean shared by all shards. It holds no per-instrument state of its own: what detection
 * needs per instrument lives in the {@link InstrumentSlot}, which only the instrument's shard thread
 * touches, so no locks are needed. Its own fields are shared, thread-safe services only.
 *
 * <p>Each trade is counted, then judged alone: {@code price × qty} at or above the instrument's
 * {@link TradeThresholds#minNotional minimum} raises an alert. Judging each aggTrade alone undercounts
 * sweeps that arrive as several fragments; that is deliberate, as the baseline sweep coalescing
 * (slice 3 of {@code .claude/plans/trade-detection-plan.md}) is compared against.
 */
@Slf4j
@Component
public class TradeDetector implements TradeSink {

    private final PipelineMetrics metrics;
    private final TradeThresholds thresholds;
    private final TradeAlertStore store;

    public TradeDetector(PipelineMetrics metrics, TradeThresholds thresholds, TradeAlertStore store) {
        this.metrics = metrics;
        this.thresholds = thresholds;
        this.store = store;
    }

    /** <b>Hot path</b>, allocation-free unless the trade alerts. */
    @Override
    public void onTrade(InstrumentSlot slot, double price, double qty, boolean aggressorBuy,
                        long tradeTimeMs, long firstTradeId, long lastTradeId) {
        Instrument instrument = slot.instrument();
        Venue venue = instrument.venue();
        metrics.recordTradeDecoded(venue);

        double notional = price * qty;
        if (notional < thresholds.minNotional(instrument)) return;

        alert(new TradeAlert(instrument, aggressorBuy, price, qty, notional, price, price, 1,
                firstTradeId, lastTradeId, tradeTimeMs, System.currentTimeMillis()));
    }

    /** Rare path: allocation and the store's lock are fine here. */
    private void alert(TradeAlert alert) {
        store.add(alert);
        metrics.recordTradeAlert(alert.instrument().venue());
        if (log.isDebugEnabled()) {
            log.debug("trade alert {} {} qty={} vwap={} notional={} fills={}",
                    alert.instrument().logName(), alert.aggressorBuy() ? "BUY" : "SELL",
                    alert.qty(), alert.vwap(), alert.notional(), alert.fills());
        }
    }
}
