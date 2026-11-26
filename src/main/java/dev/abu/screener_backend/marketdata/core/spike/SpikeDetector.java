package dev.abu.screener_backend.marketdata.core.spike;

import dev.abu.screener_backend.config.SpikeAlertProperties;
import dev.abu.screener_backend.marketdata.core.candle.CandleStore;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.KlineSink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * The core {@link KlineSink}: every decoded candle push lands here, on its instrument's shard
 * consumer thread. It updates the slot's {@link CandleStore} and runs both spike rules
 * ({@code .claude/plans/team-plans/spike-alerts-plan.md} D12, D13).
 *
 * <p>One bean shared by all shards, like {@code TradeDetector}: the candles and the re-arm state
 * live in the slot, which only the instrument's shard thread touches, so no locks are needed.
 *
 * <h3>Price (every push)</h3>
 * Over the forming candle and the {@code window - 1} closed candles before it:
 * pump {@code = (close − min low) / min low}, dump {@code = (max high − close) / max high}, in
 * percent. Measuring against the window's extreme, not the price exactly N minutes ago, catches a
 * dip-then-rip inside the window. A direction alerts when its move reaches a new level
 * {@code floor(pct / threshold)}: with 5%, at 5% and again at 10%, not at every push in between. It
 * re-arms once the move falls below {@code threshold − rearm-margin}. Pumps and dumps are
 * independent. Silent until the window is full.
 *
 * <h3>Volume (on rollover)</h3>
 * When a candle closes: the quote volume of the last {@code window} closed candles ({@code now})
 * against the {@code window} before them ({@code prev}). Alerts when {@code now} is at least
 * {@code increase-pct} above {@code prev} <em>and</em> at least {@code min-increase-usd} above it:
 * the percentage alone fires on illiquid tickers, the absolute floor alone on majors. Increases only.
 * After an alert it stays quiet until the increase falls back below {@code increase-pct}, so one
 * surge does not re-fire every minute as the window slides. Silent until both windows are full.
 *
 * <p>A push is hot path (up to four per second per instrument): no allocation unless an alert is
 * raised.
 */
@Component
public class SpikeDetector implements KlineSink {

    private final SpikeAlertProperties props;
    private final SpikeAlertStore store;
    private final PipelineMetrics metrics;
    private final LongSupplier clock;

    @Autowired
    public SpikeDetector(SpikeAlertProperties props, SpikeAlertStore store, PipelineMetrics metrics) {
        this(props, store, metrics, System::currentTimeMillis);
    }

    /** @param clock local wall clock for {@link SpikeAlert#detectedAtMs}; tests pass a fixed one */
    public SpikeDetector(SpikeAlertProperties props, SpikeAlertStore store, PipelineMetrics metrics,
                         LongSupplier clock) {
        this.props = props;
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void onKline(InstrumentSlot slot, long openTimeMs, double high, double low, double close,
                        double quoteVolume, long eventTimeMs) {
        CandleStore candles = slot.candles();
        if (candles == null) return; // no candle state: the venue binds no kline decoder

        int result = candles.update(openTimeMs, high, low, close, quoteVolume);
        if (result == CandleStore.STALE) return;
        metrics.recordKline(slot.instrument().venue());
        if (result == CandleStore.GAP) metrics.recordCandleGap(slot.instrument().venue());
        if (!props.enabled()) return;

        if (result == CandleStore.ROLLED) checkVolume(slot, candles, eventTimeMs);
        checkPrice(slot, candles, eventTimeMs);
    }

    private void checkPrice(InstrumentSlot slot, CandleStore candles, long eventTimeMs) {
        SpikeAlertProperties.Price rule = props.price();
        int closedNeeded = rule.windowMinutes() - 1;
        if (candles.filled() < closedNeeded) return;

        double low = candles.formingLow();
        double high = candles.formingHigh();
        for (int i = 0; i < closedNeeded; i++) {
            low = Math.min(low, candles.closedLow(i));
            high = Math.max(high, candles.closedHigh(i));
        }
        double close = candles.formingClose();
        if (!(low > 0) || !(high > 0)) return;

        SpikeState state = slot.spike();
        double pumpPct = (close - low) / low * 100;
        int pump = level(pumpPct, state.pumpLevel, rule);
        if (pump > state.pumpLevel) {
            emit(slot, SpikeKind.PRICE, pumpPct, low, close, rule.windowMinutes(), eventTimeMs);
        }
        state.pumpLevel = pump;

        double dumpPct = (high - close) / high * 100;
        int dump = level(dumpPct, state.dumpLevel, rule);
        if (dump > state.dumpLevel) {
            emit(slot, SpikeKind.PRICE, -dumpPct, high, close, rule.windowMinutes(), eventTimeMs);
        }
        state.dumpLevel = dump;
    }

    /**
     * The direction's new level: {@code 0} once re-armed, the previous level while the move sits in
     * the re-arm margin or below the next step, or the higher level the move reached.
     */
    private static int level(double pct, int previous, SpikeAlertProperties.Price rule) {
        if (pct < rule.thresholdPct() - rule.rearmMarginPct()) return 0;
        int reached = (int) Math.floor(pct / rule.thresholdPct());
        return Math.max(previous, reached);
    }

    private void checkVolume(InstrumentSlot slot, CandleStore candles, long eventTimeMs) {
        SpikeAlertProperties.Volume rule = props.volume();
        int window = rule.windowMinutes();
        if (candles.filled() < 2 * window) return;

        double now = 0;
        double prev = 0;
        for (int i = 0; i < window; i++) {
            now += candles.closedQuoteVolume(i);
            prev += candles.closedQuoteVolume(window + i);
        }
        double increase = now - prev;
        double pct = prev > 0 ? increase / prev * 100
                : increase > 0 ? Double.POSITIVE_INFINITY : 0;

        SpikeState state = slot.spike();
        if (pct < rule.increasePct()) {
            state.volumeArmed = true;
            return;
        }
        if (state.volumeArmed && increase >= rule.minIncreaseUsd()) {
            state.volumeArmed = false;
            emit(slot, SpikeKind.VOLUME, pct, prev, now, window, eventTimeMs);
        }
    }

    private void emit(InstrumentSlot slot, SpikeKind kind, double pct, double from, double to,
                      int windowMinutes, long eventTimeMs) {
        store.add(new SpikeAlert(slot.instrument(), kind, pct, from, to, windowMinutes, eventTimeMs,
                clock.getAsLong()));
        metrics.recordSpikeAlert(slot.instrument().venue(), kind);
    }
}
