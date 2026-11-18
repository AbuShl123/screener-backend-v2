package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.config.ExchangesProperties.StreamProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;

/**
 * MEXC spot's stream wire protocol ({@code wss://wbs-api.mexc.com/ws}), one instance per channel:
 * {@link #depth} ({@code spot@public.aggre.depth.v3.api.pb@100ms}) or {@link #deals}
 * ({@code spot@public.aggre.deals.v3.api.pb@100ms}).
 *
 * <p>Subscribes with {@code {"method":"SUBSCRIPTION","params":["<channel>@BTCUSDT",…]}} and keeps the
 * connection alive with a text {@code {"method":"PING"}}: the server drops a connection whose
 * subscriptions are all quiet after ~60s without one, with no close frame. Both channels share the
 * subscribe frame, the 30-per-connection limit, the acks and the keepalive.
 *
 * <h3>Frames, as delivered</h3>
 * {@code external-docs/mexc/mexc-spot-depth-empirical.md} §1 and §5,
 * {@code external-docs/mexc/mexc-trades-empirical.md} §1:
 * <ul>
 *   <li><b>Pushes are binary</b> (Protobuf), routed by the wrapper's {@code symbol} field via
 *       {@link MexcSpotFrameReader#symbolRange}, which precedes the body on the wire. The wrapper is
 *       the same on both channels (depth body 313, deals body 314), so routing does not look at the
 *       body.</li>
 *   <li><b>Control replies are text</b>, all shaped {@code {"id":0,"code":0,"msg":…}}: the PONG, and
 *       subscribe acks. {@code code} is 0 even when a subscription is rejected, so the only sign of
 *       one is {@code msg} containing {@code Not Subscribed successfully! [<ch>,…]}, logged at WARN.</li>
 * </ul>
 */
@Slf4j
public final class MexcSpotStreamProtocol implements StreamProtocol {

    /** Hard server limit, counted cumulatively per connection ({@code mexc-ws-limits-empirical.md}). */
    static final int MAX_SUBSCRIPTIONS_PER_CONNECTION = 30;
    /** Depth pushes carry body field 313, the one {@link MexcSpotFrameReader} reads. */
    static final String DEPTH_CHANNEL_PREFIX = "spot@public.aggre.depth.v3.api.pb@100ms@";
    /** Deal pushes carry body field 314. */
    static final String DEALS_CHANNEL_PREFIX = "spot@public.aggre.deals.v3.api.pb@100ms@";
    static final String PING = "{\"method\":\"PING\"}";

    private static final String PONG = "\"msg\":\"PONG\"";
    private static final String REJECTED = "Not Subscribed successfully! [";

    private final StreamKey stream;
    private final StreamProperties props;
    private final String channelPrefix;

    /**
     * Order-book diffs, 100 ms aggregated.
     *
     * @throws IllegalArgumentException as {@link #MexcSpotStreamProtocol}
     */
    public static MexcSpotStreamProtocol depth(Venue venue, StreamProperties props) {
        return new MexcSpotStreamProtocol(new StreamKey(venue, StreamKind.DEPTH), props, DEPTH_CHANNEL_PREFIX);
    }

    /**
     * Deals (fills), 100 ms batches.
     *
     * @throws IllegalArgumentException as {@link #MexcSpotStreamProtocol}
     */
    public static MexcSpotStreamProtocol deals(Venue venue, StreamProperties props) {
        return new MexcSpotStreamProtocol(new StreamKey(venue, StreamKind.TRADE), props, DEALS_CHANNEL_PREFIX);
    }

    /**
     * @throws IllegalArgumentException unless {@code max-streams-per-connection} and
     *         {@code subscribe-chunk-size} are within the server's 30 subscriptions per connection
     *         (beyond it, subscriptions are rejected with {@code code 0})
     */
    private MexcSpotStreamProtocol(StreamKey stream, StreamProperties props, String channelPrefix) {
        if (props.maxStreamsPerConnection() > MAX_SUBSCRIPTIONS_PER_CONNECTION) {
            throw new IllegalArgumentException(stream + ": max-streams-per-connection must be <= "
                    + MAX_SUBSCRIPTIONS_PER_CONNECTION + ", got " + props.maxStreamsPerConnection());
        }
        if (props.subscribeChunkSize() > MAX_SUBSCRIPTIONS_PER_CONNECTION) {
            throw new IllegalArgumentException(stream + ": subscribe-chunk-size must be <= "
                    + MAX_SUBSCRIPTIONS_PER_CONNECTION + ", got " + props.subscribeChunkSize());
        }
        this.stream = stream;
        this.props = props;
        this.channelPrefix = channelPrefix;
    }

    /** Symbols as listed (upper case): MEXC rejects {@code ethusdt}. No request id; acks carry {@code id 0}. */
    @Override
    public String subscribeFrame(List<Instrument> chunk, int requestId) {
        StringBuilder sb = new StringBuilder(40 + chunk.size() * 64);
        sb.append("{\"method\":\"SUBSCRIPTION\",\"params\":[");
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(channelPrefix).append(chunk.get(i).nativeSymbol()).append('"');
        }
        return sb.append("]}").toString();
    }

    @Override
    public String routingKey(Instrument instrument) {
        return instrument.nativeSymbol();
    }

    /**
     * A malformed frame is not thrown: java-websocket would only log it. It is reported at debug and
     * returned {@link #IGNORED}, which core counts and logs at WARN, rate-limited — so format drift on
     * every frame cannot flood the log.
     */
    @Override
    public int route(ByteBuffer frame, SubscriptionIndex index) {
        long range;
        try {
            range = MexcSpotFrameReader.symbolRange(frame);
        } catch (IllegalStateException e) {
            log.debug("[{}] Unroutable binary frame: {}", stream, e.getMessage());
            return IGNORED;
        }
        if (range == MexcSpotFrameReader.NO_RANGE) return IGNORED;
        return index.resolve(frame, MexcSpotFrameReader.start(range), MexcSpotFrameReader.end(range));
    }

    /** Text frames are control replies only; pushes never arrive as text. */
    @Override
    public int route(String frame, SubscriptionIndex index) {
        if (frame.contains(PONG)) {
            return IGNORED;
        }
        int rejected = frame.indexOf(REJECTED);
        if (rejected != -1 && rejected + REJECTED.length() < frame.length()
                && frame.charAt(rejected + REJECTED.length()) != ']') {
            // Without this the only symptom would be books that never sync, or symbols with no trades.
            log.warn("[{}] Subscription rejected: {}", stream, frame);
        } else {
            // Acks arrive once per subscribe frame, in any channel order; counting them against
            // subscriptions belongs to the venue health surface.
            log.debug("[{}] Control frame: {}", stream, frame);
        }
        return IGNORED;
    }

    @Override
    public Heartbeat heartbeat() {
        return new Heartbeat.TextPing(Duration.ofSeconds(props.heartbeatIntervalSeconds()), PING);
    }
}
