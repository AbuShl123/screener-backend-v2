package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

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
 * sweep when it has the same aggressor side and
 * <ol>
 *   <li>when both taker-order ids are known (MEXC spot): the same taker order. Nothing else is
 *       checked.</li>
 *   <li>otherwise:
 *     <ul>
 *       <li>the same venue trade time,</li>
 *       <li>a price that moves in the aggressor's direction (a buy walks up, a sell walks down):
 *           strictly where one trade aggregates a taker order's fills at one price (Binance, where
 *           an equal price is a second order in the same millisecond), or equal too where one trade
 *           is one fill against one resting order ({@link TradeState#makerFills}, MEXC futures),
 *           and</li>
 *       <li>when both ids are known, a first trade id right after the sweep's last one.</li>
 *     </ul>
 *   </li>
 * </ol>
 * Any other trade closes the open sweep, judges it, and opens the next sweep. The rules and the data
 * behind them are in {@code .claude/plans/trade-detection-plan.md} §10.3 (Binance) and
 * {@code .claude/plans/trade-mexc-plan.md} §2.4 (MEXC).
 *
 * <h3>Closing</h3>
 * By default only the next trade can show that a sweep is over, so a sweep is judged when the
 * instrument trades again. On a quiet symbol that can take seconds (plan §10.4). A venue whose
 * taker orders always arrive within one frame ({@link TradeState#closeAtFrameEnd}) has its sweep
 * judged by {@link #onFrameEnd} instead, right after the frame that carried it. A venue with a
 * {@code sweep-idle-close-ms} also has its sweep judged by {@link #closeIfIdle} once no fill has
 * arrived for that long; the shard tick calls it ({@code .claude/plans/trade-mexc-plan.md} §2.6). A
 * fill that would have continued an idle-closed sweep is counted as an <b>idle split</b>.
 *
 * <p>Filtered trades ({@link #onFiltered}) are only counted. They never touch the sweep.
 */
@Slf4j
@Component
public class TradeDetector implements TradeSink {

    private final PipelineMetrics metrics;
    private final TradeThresholds thresholds;
    private final TradeAlertStore store;
    /** Local wall clock, epoch millis: {@code System::currentTimeMillis} except in replay tests. */
    private final LongSupplier clock;
    /** {@code sweep-idle-close-ms} by {@code Venue.ordinal()}; {@code 0} for a venue without an idle close. */
    private final long[] idleCloseMs = new long[Venue.values().length];

    @Autowired
    public TradeDetector(PipelineMetrics metrics, TradeAlertProperties props, TradeThresholds thresholds,
                         TradeAlertStore store) {
        this(metrics, props, thresholds, store, System::currentTimeMillis);
    }

    public TradeDetector(PipelineMetrics metrics, TradeAlertProperties props, TradeThresholds thresholds,
                         TradeAlertStore store, LongSupplier clock) {
        this.metrics = metrics;
        this.thresholds = thresholds;
        this.store = store;
        this.clock = clock;
        props.sweepIdleCloseMs().forEach((venue, ms) -> idleCloseMs[venue.ordinal()] = ms);
    }

    /** <b>Hot path</b>, allocation-free unless the trade closes a sweep that alerts. */
    @Override
    public void onTrade(InstrumentSlot slot, double price, double qty, boolean aggressorBuy,
                        long tradeTimeMs, long firstTradeId, long lastTradeId, long takerOrderId) {
        metrics.recordTradeDecoded(slot.instrument().venue());

        long now = clock.getAsLong();
        TradeState sweep = slot.trade();
        if (sweep.open) {
            if (continues(sweep, price, aggressorBuy, tradeTimeMs, firstTradeId, takerOrderId)) {
                sweep.add(price, qty, lastTradeId, now);
                return;
            }
            close(slot.instrument(), sweep);
        } else if (sweep.closedByIdle && continues(sweep, price, aggressorBuy, tradeTimeMs, firstTradeId, takerOrderId)) {
            // The closed sweep's fields are still in place: this fill belonged to it.
            metrics.recordTradeIdleSplit(slot.instrument().venue());
        }
        sweep.open(price, qty, aggressorBuy, tradeTimeMs, firstTradeId, lastTradeId, takerOrderId, now);
    }

    /** <b>Hot path</b>: a trade the decoder dropped by venue policy. Counted, never judged. */
    @Override
    public void onFiltered(InstrumentSlot slot) {
        metrics.recordTradeFiltered(slot.instrument().venue());
    }

    /**
     * <b>Hot path</b>: called after every decode of a {@code TRADE} frame, successful or not. Judges
     * the open sweep if the instrument's venue closes sweeps at the end of a frame; does nothing
     * otherwise.
     */
    public void onFrameEnd(InstrumentSlot slot) {
        TradeState sweep = slot.trade();
        if (sweep != null && sweep.closeAtFrameEnd && sweep.open) close(slot.instrument(), sweep);
    }

    /**
     * <b>Hot path</b>, on the shard tick: judges the open sweep if the instrument's venue has an idle
     * close and no fill has arrived for at least that long. Does nothing for a slot without trade
     * state, a closed sweep or a venue without an idle close. Must run on the instrument's shard
     * thread, like every other access to the sweep: the tick only tells that thread to look.
     *
     * <p>It checks the sweep that is open when the tick is handled, against that sweep's own last
     * fill. A frame handled just before the tick has already opened or extended it, so its fresh
     * {@code lastFillAtMs} keeps it open.
     */
    public void closeIfIdle(InstrumentSlot slot) {
        TradeState sweep = slot.trade();
        if (sweep == null || !sweep.open) return;
        Instrument instrument = slot.instrument();
        long idle = idleCloseMs[instrument.venue().ordinal()];
        if (idle == 0 || clock.getAsLong() - sweep.lastFillAtMs < idle) return;
        sweep.closedByIdle = true;
        metrics.recordTradeIdleClose(instrument.venue());
        close(instrument, sweep);
    }

    private static boolean continues(TradeState sweep, double price, boolean aggressorBuy,
                                     long tradeTimeMs, long firstTradeId, long takerOrderId) {
        if (aggressorBuy != sweep.aggressorBuy) return false;
        if (takerOrderId >= 0 && sweep.takerOrderId >= 0) return takerOrderId == sweep.takerOrderId;
        if (tradeTimeMs != sweep.tradeTimeMs) return false;
        if (sweep.makerFills
                ? (aggressorBuy ? price < sweep.lastPrice : price > sweep.lastPrice)
                : (aggressorBuy ? price <= sweep.lastPrice : price >= sweep.lastPrice)) {
            return false;
        }
        return firstTradeId < 0 || sweep.lastTradeId < 0 || firstTradeId == sweep.lastTradeId + 1;
    }

    /** Judges the finished sweep and marks it closed. Its fields stay until the next {@code open}. */
    private void close(Instrument instrument, TradeState sweep) {
        sweep.open = false;
        double min = thresholds.minNotional(instrument);
        if (sweep.sumNotional < min) return;

        alert(new TradeAlert(instrument, sweep.aggressorBuy, sweep.sumNotional / sweep.sumQty,
                sweep.sumQty, sweep.sumNotional, min, sweep.firstPrice, sweep.lastPrice, sweep.fills,
                sweep.levels, sweep.maxLevelNotional < min, sweep.firstTradeId, sweep.lastTradeId,
                sweep.tradeTimeMs, clock.getAsLong()));
    }

    /** Rare path: allocation and the store's lock are fine here. */
    private void alert(TradeAlert alert) {
        store.add(alert);
        metrics.recordTradeAlert(alert.instrument().venue(), alert.fills(), alert.coalescedOnly());
        if (log.isDebugEnabled()) {
            log.debug("trade alert {} {} qty={} vwap={} notional={} fills={} levels={} coalescedOnly={}",
                    alert.instrument().logName(), alert.aggressorBuy() ? "BUY" : "SELL", alert.qty(),
                    alert.vwap(), alert.notional(), alert.fills(), alert.levels(), alert.coalescedOnly());
        }
    }
}
