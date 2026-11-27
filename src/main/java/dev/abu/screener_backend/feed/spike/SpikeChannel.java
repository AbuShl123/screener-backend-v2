package dev.abu.screener_backend.feed.spike;

import dev.abu.screener_backend.config.SpikeAlertProperties;
import dev.abu.screener_backend.feed.Envelope;
import dev.abu.screener_backend.feed.FeedChannel;
import dev.abu.screener_backend.marketdata.core.spike.SpikeAlert;
import dev.abu.screener_backend.marketdata.core.spike.SpikeAlertStore;
import dev.abu.screener_backend.marketdata.core.spike.SpikeKind;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Price and volume spikes: one {@code PRICE_SPIKE} or {@code VOLUME_SPIKE} message per alert to
 * every session, and the most recent {@code replay-size} alerts in every {@code SNAPSHOT}
 * ({@code websocket-feed-api.md} §3.10).
 *
 * <p>The same model as {@code TradeChannel}: one global stream, alerts are events, each sent live
 * exactly once in the tick after it was drained, and the replay is history the client merges by
 * {@code id}. An alert added after this tick's {@link #drain()} is in neither this tick's snapshot
 * nor its updates; it arrives next tick.
 *
 * <p>With {@code screener.spike-alerts.delivery-enabled: false} (shadow mode) alerts are still
 * drained, so turning delivery on later does not flush a backlog, but nothing is sent and the replay
 * stays empty. They remain visible on {@code /api/monitoring/spike-alerts}.
 *
 * <p>All methods run on the broadcaster thread, so the state is plain fields. Bodies are built once
 * per alert and shared by every session and every later snapshot.
 */
@Slf4j
@Component
@Order(3)
public class SpikeChannel implements FeedChannel {

    /** Wire {@code type} of a price spike. */
    static final String PRICE_TYPE = "PRICE_SPIKE";
    /** Wire {@code type} of a volume spike. */
    static final String VOLUME_TYPE = "VOLUME_SPIKE";
    private static final double SCALE = 100;

    private final SpikeAlertStore store;
    private final boolean deliveryEnabled;
    private final int replaySize;

    /** The store's alert count as of the last drain. */
    private long seen;
    private final List<SpikeAlert> scratch = new ArrayList<>();
    /** This tick's bodies, oldest first. */
    private final List<String> tickBodies = new ArrayList<>();
    /** The last {@code replaySize} bodies, oldest first. */
    private final ArrayDeque<String> replay;
    private final StringBuilder sb = new StringBuilder(384);

    public SpikeChannel(SpikeAlertStore store, SpikeAlertProperties props) {
        this.store = store;
        this.deliveryEnabled = props.deliveryEnabled();
        this.replaySize = props.replaySize();
        this.replay = new ArrayDeque<>(replaySize);
    }

    /** Runs with no sessions too, so the replay fills from startup. A tick with no alerts allocates nothing. */
    @Override
    public void drain() {
        scratch.clear();
        tickBodies.clear();
        long total = store.drainSince(seen, scratch);
        long lost = (total - seen) - scratch.size();
        if (lost > 0) {
            log.warn("{} spike alert(s) were dropped from the store before the feed drained them "
                    + "(recent-capacity {} too small for the broadcaster's pace)", lost, store.capacity());
        }
        seen = total;

        if (deliveryEnabled) {
            for (SpikeAlert alert : scratch) {
                String body = buildBody(alert);
                tickBodies.add(body);
                if (replay.size() == replaySize) replay.removeFirst();
                replay.addLast(body);
            }
        }
        scratch.clear(); // don't keep alerts reachable between ticks
    }

    @Override
    public void collectUpdates(UserWebSocketSession session, List<String> out) {
        out.addAll(tickBodies);
    }

    @Override
    public void collectSnapshot(UserWebSocketSession session, List<String> out) {
        out.addAll(replay);
    }

    /**
     * <pre>
     * {"type":"PRICE_SPIKE",…,"data":{"id","time","direction","pct","fromPrice","toPrice","windowSec"}}
     * {"type":"VOLUME_SPIKE",…,"data":{"id","time","pct","prevVolume","volume","windowSec"}}
     * </pre>
     * Fields in contract order. The id is venue name, native symbol, kind and digits, so it needs no
     * JSON escaping. A volume {@code pct} is {@code null} when the previous window traded nothing.
     */
    String buildBody(SpikeAlert alert) {
        sb.setLength(0);
        boolean price = alert.kind() == SpikeKind.PRICE;
        Envelope.head(sb, price ? PRICE_TYPE : VOLUME_TYPE, alert.instrument());
        sb.append("{\"id\":\"").append(alert.id()).append('"');
        sb.append(",\"time\":").append(alert.timeMs());
        if (price) {
            sb.append(",\"direction\":\"").append(alert.up() ? "UP" : "DOWN").append('"');
            sb.append(",\"pct\":").append(round(alert.pct()));
            sb.append(",\"fromPrice\":").append(alert.from());
            sb.append(",\"toPrice\":").append(alert.to());
        } else {
            sb.append(",\"pct\":");
            if (Double.isFinite(alert.pct())) sb.append(round(alert.pct()));
            else sb.append("null");
            sb.append(",\"prevVolume\":").append(round(alert.from()));
            sb.append(",\"volume\":").append(round(alert.to()));
        }
        sb.append(",\"windowSec\":").append(alert.windowMinutes() * 60);
        sb.append("}}");
        return sb.toString();
    }

    private static double round(double v) {
        return Math.round(v * SCALE) / SCALE;
    }
}
