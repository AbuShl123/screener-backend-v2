package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The core {@link TradeSink}: every decoded trade of every venue lands here, on its instrument's
 * shard consumer thread.
 *
 * <p>One bean shared by all shards. It holds no per-instrument state of its own: the open sweep
 * lives in the slot's {@link TradeState}, which only the instrument's shard thread touches, so no
 * locks are needed. Its own fields are shared, thread-safe services only.
 *
 * <h3>Sweep coalescing</h3>
 * One taker order that walks several price levels reaches us as several trades. They are folded
 * into one sweep and judged as a whole: the sweep's summed notional at or above the instrument's
 * {@link TradeThresholds#minNotional minimum} raises one alert. A trade <b>continues</b> the open
 * sweep when it has
 * <ul>
 *   <li>the same aggressor side and the same venue trade time,</li>
 *   <li>a price that moves strictly in the aggressor's direction (a buy walks up, a sell walks
 *       down; an equal price is a second order in the same millisecond), and</li>
 *   <li>when both ids are known, a first trade id right after the sweep's last one.</li>
 * </ul>
 * Any other trade closes the open sweep, judges it, and opens the next sweep. The rule and the data
 * behind it are in {@code .claude/plans/trade-detection-plan.md} §10.3.
 *
 * <p>Only the next trade can show that a sweep is over, so a sweep is judged when the instrument
 * trades again. On a quiet symbol that can take seconds (plan §10.4).
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

    /** <b>Hot path</b>, allocation-free unless the trade closes a sweep that alerts. */
    @Override
    public void onTrade(InstrumentSlot slot, double price, double qty, boolean aggressorBuy,
                        long tradeTimeMs, long firstTradeId, long lastTradeId) {
        metrics.recordTradeDecoded(slot.instrument().venue());

        TradeState sweep = slot.trade();
        if (sweep.open && continues(sweep, price, aggressorBuy, tradeTimeMs, firstTradeId)) {
            sweep.add(price, qty, lastTradeId);
            return;
        }
        if (sweep.open) close(slot.instrument(), sweep);
        sweep.open(price, qty, aggressorBuy, tradeTimeMs, firstTradeId, lastTradeId);
    }

    private static boolean continues(TradeState sweep, double price, boolean aggressorBuy,
                                     long tradeTimeMs, long firstTradeId) {
        if (aggressorBuy != sweep.aggressorBuy || tradeTimeMs != sweep.tradeTimeMs) return false;
        if (aggressorBuy ? price <= sweep.lastPrice : price >= sweep.lastPrice) return false;
        return firstTradeId < 0 || sweep.lastTradeId < 0 || firstTradeId == sweep.lastTradeId + 1;
    }

    /** Judges the finished sweep. Leaves the state to be overwritten by the next {@code open}. */
    private void close(Instrument instrument, TradeState sweep) {
        double min = thresholds.minNotional(instrument);
        if (sweep.sumNotional < min) return;

        alert(new TradeAlert(instrument, sweep.aggressorBuy, sweep.sumNotional / sweep.sumQty,
                sweep.sumQty, sweep.sumNotional, sweep.firstPrice, sweep.lastPrice, sweep.fills,
                sweep.maxFillNotional < min, sweep.firstTradeId, sweep.lastTradeId, sweep.tradeTimeMs,
                System.currentTimeMillis()));
    }

    /** Rare path: allocation and the store's lock are fine here. */
    private void alert(TradeAlert alert) {
        store.add(alert);
        metrics.recordTradeAlert(alert.instrument().venue(), alert.fills(), alert.coalescedOnly());
        if (log.isDebugEnabled()) {
            log.debug("trade alert {} {} qty={} vwap={} notional={} fills={} coalescedOnly={}",
                    alert.instrument().logName(), alert.aggressorBuy() ? "BUY" : "SELL",
                    alert.qty(), alert.vwap(), alert.notional(), alert.fills(), alert.coalescedOnly());
        }
    }
}
