package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

/**
 * MEXC futures' depth-stream wire protocol ({@code wss://contract.mexc.com/edge}).
 *
 * <p>Subscribes one symbol per frame with {@code {"method":"sub.depth","param":{"symbol":"BTC_USDT"}}},
 * routes {@code push.depth} frames by their {@code "symbol"} field (the native symbol), and keeps
 * the connection alive with a text {@code {"method":"ping"}} — MEXC drops a connection after 60s
 * without one.
 *
 * <h3>Frame shapes, as delivered</h3>
 * The field order differs from MEXC's docs ({@code external-docs/mexc/mexc-depth-versioning-empirical.md}):
 * <pre>
 * {"symbol":"BTC_USDT","data":{…},"channel":"push.depth","ts":…}   data — channel <em>last</em>
 * {"channel":"rs.sub.depth","data":"success","ts":…}                subscribe ack — no symbol echoed
 * {"channel":"pong","data":…,"ts":…}                                heartbeat reply
 * </pre>
 * So a frame that <em>starts</em> with {@code "symbol"} is a depth push and its symbol sits at a
 * fixed offset, while a frame that starts with {@code "channel"} is a control frame — or a depth
 * push in the documented order, which is still routed in case MEXC ever switches to it. A
 * symbol-led frame's channel is not checked: only {@code sub.depth} is ever subscribed, and the
 * check would scan the whole frame to reach a field that comes last.
 */
@Slf4j
public final class MexcFuturesStreamProtocol implements StreamProtocol {

    static final String PING = "{\"method\":\"ping\"}";

    private static final String SYMBOL_LEAD = "{\"symbol\":\"";
    private static final String CHANNEL_LEAD = "{\"channel\":\"";
    private static final String SYMBOL_FIELD = "\"symbol\":\"";

    // Channel values, each with its closing quote so a prefix never matches a longer name.
    private static final String PUSH_DEPTH = "push.depth\"";
    private static final String PONG = "pong\"";
    private static final String SUB_ACK = "rs.sub.depth\"";
    private static final String ERROR = "rs.error\"";
    private static final String ACK_SUCCESS = "\"data\":\"success\"";

    private final Venue venue;
    private final VenueProperties props;

    /**
     * @throws IllegalArgumentException unless {@code subscribe-chunk-size} is 1 ({@code sub.depth}
     *         takes one symbol, so a larger chunk would silently subscribe only its first)
     */
    public MexcFuturesStreamProtocol(Venue venue, VenueProperties props) {
        if (props.subscribeChunkSize() != 1) {
            throw new IllegalArgumentException(venue + ": subscribe-chunk-size must be 1 (sub.depth takes one symbol), got "
                    + props.subscribeChunkSize());
        }
        this.venue = venue;
        this.props = props;
    }

    @Override
    public String subscribeFrame(List<Instrument> chunk, int requestId) {
        // sub.depth carries no request id; acks are matched by count, not by id.
        return "{\"method\":\"sub.depth\",\"param\":{\"symbol\":\"" + chunk.getFirst().nativeSymbol() + "\"}}";
    }

    @Override
    public String routingKey(Instrument instrument) {
        return instrument.nativeSymbol();
    }

    @Override
    public int route(String frame, SubscriptionIndex index) {
        if (frame.startsWith(SYMBOL_LEAD)) {
            return resolveSymbol(frame, SYMBOL_LEAD.length(), index);
        }
        if (!frame.startsWith(CHANNEL_LEAD)) {
            return IGNORED;
        }
        int channel = CHANNEL_LEAD.length();
        if (frame.startsWith(PUSH_DEPTH, channel)) {
            int sPos = frame.indexOf(SYMBOL_FIELD, channel);
            if (sPos == -1) return IGNORED;
            return resolveSymbol(frame, sPos + SYMBOL_FIELD.length(), index);
        }
        if (!frame.startsWith(PONG, channel)) {
            onControlFrame(frame, channel);
        }
        return IGNORED;
    }

    private static int resolveSymbol(String frame, int start, SubscriptionIndex index) {
        int end = frame.indexOf('"', start);
        if (end == -1) return IGNORED;
        return index.resolve(frame, start, end);
    }

    /** Acks arrive once per subscribed symbol; errors are rare. Neither is per-push traffic. */
    private void onControlFrame(String frame, int channel) {
        if (frame.startsWith(SUB_ACK, channel)) {
            if (frame.contains(ACK_SUCCESS)) {
                // No symbol echoed, so a single ack says nothing about which book it is for.
                // Counting acks against subscriptions belongs to the venue health surface.
                log.debug("[{}] sub.depth acked", venue);
            } else {
                log.warn("[{}] sub.depth rejected: {}", venue, frame);
            }
        } else if (frame.startsWith(ERROR, channel)) {
            // Without this the only symptom would be books that never sync.
            log.warn("[{}] Stream error frame: {}", venue, frame);
        } else {
            log.debug("[{}] Unrecognised control frame: {}", venue, frame);
        }
    }

    @Override
    public Heartbeat heartbeat() {
        return new Heartbeat.TextPing(Duration.ofSeconds(props.heartbeatIntervalSeconds()), PING);
    }
}
