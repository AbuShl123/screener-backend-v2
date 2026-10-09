package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;

/**
 * MEXC spot's depth-stream wire protocol ({@code wss://wbs-api.mexc.com/ws}).
 *
 * <p>Subscribes with {@code {"method":"SUBSCRIPTION","params":["spot@public.aggre.depth.v3.api.pb@100ms@BTCUSDT",…]}}
 * and keeps the connection alive with a text {@code {"method":"PING"}}: the server drops a connection
 * whose subscriptions are all quiet after ~60s without one, with no close frame.
 *
 * <h3>Frames, as delivered</h3>
 * {@code external-docs/mexc/mexc-spot-depth-empirical.md} §1 and §5:
 * <ul>
 *   <li><b>Depth pushes are binary</b> (Protobuf), routed by the wrapper's {@code symbol} field via
 *       {@link MexcSpotFrameReader#symbolRange}, which precedes the levels on the wire.</li>
 *   <li><b>Control replies are text</b>, all shaped {@code {"id":0,"code":0,"msg":…}}: the PONG, and
 *       subscribe acks. {@code code} is 0 even when a subscription is rejected, so the only sign of
 *       one is {@code msg} containing {@code Not Subscribed successfully! [<ch>,…]}, logged at WARN.</li>
 * </ul>
 */
@Slf4j
public final class MexcSpotStreamProtocol implements StreamProtocol {

    /** Hard server limit, counted cumulatively per connection ({@code mexc-ws-limits-empirical.md}). */
    static final int MAX_SUBSCRIPTIONS_PER_CONNECTION = 30;
    /** The only channel {@link MexcSpotFrameReader} reads: its pushes carry body field 313. */
    static final String AGGRE_DEPTH_TOPIC_PREFIX = "spot@public.aggre.depth.v3.api.pb@";
    static final String PING = "{\"method\":\"PING\"}";

    private static final String PONG = "\"msg\":\"PONG\"";
    private static final String REJECTED = "Not Subscribed successfully! [";

    private final Venue venue;
    private final VenueProperties props;

    /**
     * @throws IllegalArgumentException unless {@code max-streams-per-connection} and
     *         {@code subscribe-chunk-size} are within the server's 30 subscriptions per connection
     *         (beyond it, subscriptions are rejected with {@code code 0}), and {@code stream-topic} is
     *         an {@code aggre.depth} channel ending in {@code @{symbol}} (pushes are routed by the
     *         wrapper's symbol, and only that channel's body is understood)
     */
    public MexcSpotStreamProtocol(Venue venue, VenueProperties props) {
        if (props.maxStreamsPerConnection() > MAX_SUBSCRIPTIONS_PER_CONNECTION) {
            throw new IllegalArgumentException(venue + ": max-streams-per-connection must be <= "
                    + MAX_SUBSCRIPTIONS_PER_CONNECTION + ", got " + props.maxStreamsPerConnection());
        }
        if (props.subscribeChunkSize() > MAX_SUBSCRIPTIONS_PER_CONNECTION) {
            throw new IllegalArgumentException(venue + ": subscribe-chunk-size must be <= "
                    + MAX_SUBSCRIPTIONS_PER_CONNECTION + ", got " + props.subscribeChunkSize());
        }
        String topic = props.streamTopic();
        if (!topic.startsWith(AGGRE_DEPTH_TOPIC_PREFIX) || !topic.endsWith("@" + VenueProperties.SYMBOL_PLACEHOLDER)) {
            throw new IllegalArgumentException(venue + ": stream-topic must be " + AGGRE_DEPTH_TOPIC_PREFIX
                    + "<interval>@" + VenueProperties.SYMBOL_PLACEHOLDER + ", got " + topic);
        }
        this.venue = venue;
        this.props = props;
    }

    /** Symbols as listed (upper case): MEXC rejects {@code ethusdt}. No request id; acks carry {@code id 0}. */
    @Override
    public String subscribeFrame(List<Instrument> chunk, int requestId) {
        StringBuilder sb = new StringBuilder(40 + chunk.size() * 64);
        sb.append("{\"method\":\"SUBSCRIPTION\",\"params\":[");
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(props.streamTopic(chunk.get(i).nativeSymbol())).append('"');
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
            log.debug("[{}] Unroutable binary frame: {}", venue, e.getMessage());
            return IGNORED;
        }
        if (range == MexcSpotFrameReader.NO_RANGE) return IGNORED;
        return index.resolve(frame, MexcSpotFrameReader.start(range), MexcSpotFrameReader.end(range));
    }

    /** Text frames are control replies only; depth never arrives as text. */
    @Override
    public int route(String frame, SubscriptionIndex index) {
        if (frame.contains(PONG)) {
            return IGNORED;
        }
        int rejected = frame.indexOf(REJECTED);
        if (rejected != -1 && rejected + REJECTED.length() < frame.length()
                && frame.charAt(rejected + REJECTED.length()) != ']') {
            // Without this the only symptom would be books that never sync.
            log.warn("[{}] Subscription rejected: {}", venue, frame);
        } else {
            // Acks arrive once per subscribe frame, in any channel order; counting them against
            // subscriptions belongs to the venue health surface.
            log.debug("[{}] Control frame: {}", venue, frame);
        }
        return IGNORED;
    }

    @Override
    public Heartbeat heartbeat() {
        return new Heartbeat.TextPing(Duration.ofSeconds(props.heartbeatIntervalSeconds()), PING);
    }
}
