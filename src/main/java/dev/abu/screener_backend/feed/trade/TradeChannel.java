package dev.abu.screener_backend.feed.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.feed.Envelope;
import dev.abu.screener_backend.feed.FeedChannel;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlert;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlertStore;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Large-trade alerts: one {@code TRADE} message per alert to every session, and the most recent
 * {@code replay-size} alerts in every {@code SNAPSHOT} ({@code websocket-feed-api.md} §3.8).
 *
 * <p>One global stream: every session gets every alert, whatever its classification rules. Alerts
 * are events, not state, so each is sent live exactly once, in the tick after it was drained from
 * {@link TradeAlertStore}, and the replay is history the client merges by {@code id}.
 *
 * <p><b>Exactly once.</b> The replay holds only drained alerts. An alert added after this tick's
 * {@link #drain()} is in neither this tick's snapshot nor its updates; it arrives next tick. A
 * session that gets a {@code SNAPSHOT} this tick gets this tick's alerts inside it and no update
 * batch (the broadcaster's rule), so it sees each alert once.
 *
 * <p>All methods run on the broadcaster thread, so the state is plain fields. Bodies are built once
 * per alert and shared by every session and every later snapshot.
 */
@Slf4j
@Component
@Order(1)
public class TradeChannel implements FeedChannel {

    /** Wire {@code type} of every message this channel sends. */
    static final String TYPE = "TRADE";
    private static final double NOTIONAL_SCALE = 100;

    private final TradeAlertStore store;
    private final int replaySize;

    /** The store's alert count as of the last drain. */
    private long seen;
    private final List<TradeAlert> scratch = new ArrayList<>();
    /** This tick's bodies, oldest first. */
    private final List<String> tickBodies = new ArrayList<>();
    /** The last {@code replaySize} bodies, oldest first. */
    private final ArrayDeque<String> replay;
    private final StringBuilder sb = new StringBuilder(512);

    public TradeChannel(TradeAlertStore store, TradeAlertProperties props) {
        this.store = store;
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
            log.warn("{} trade alert(s) were dropped from the store before the feed drained them "
                    + "(recent-capacity {} too small for the broadcaster's pace)", lost, store.capacity());
        }
        seen = total;

        for (TradeAlert alert : scratch) {
            String body = buildBody(alert);
            tickBodies.add(body);
            if (replay.size() == replaySize) replay.removeFirst();
            replay.addLast(body);
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
     * {@code {"type":"TRADE",…,"data":{"id",…,"threshold"}}}, fields in contract order. The id is
     * venue name, native symbol and digits, so it needs no JSON escaping.
     */
    private String buildBody(TradeAlert alert) {
        sb.setLength(0);
        Envelope.head(sb, TYPE, alert.instrument());
        sb.append("{\"id\":\"").append(alert.id()).append('"');
        sb.append(",\"side\":\"").append(alert.aggressorBuy() ? "BUY" : "SELL").append('"');
        sb.append(",\"time\":").append(alert.tradeTimeMs());
        sb.append(",\"notional\":").append(Math.round(alert.notional() * NOTIONAL_SCALE) / NOTIONAL_SCALE);
        sb.append(",\"qty\":").append(alert.qty());
        sb.append(",\"avgPrice\":").append(alert.vwap());
        sb.append(",\"firstPrice\":").append(alert.firstPrice());
        sb.append(",\"lastPrice\":").append(alert.lastPrice());
        sb.append(",\"levels\":").append(alert.fills());
        sb.append(",\"threshold\":").append(alert.minNotional());
        sb.append("}}");
        return sb.toString();
    }
}
