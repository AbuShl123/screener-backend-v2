package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

/**
 * Bybit's order-book stream wire protocol, shared by spot ({@code /v5/public/spot}) and linear
 * ({@code /v5/public/linear}), one instance per venue.
 *
 * <p>Subscribes with {@code {"req_id":"N","op":"subscribe","args":["orderbook.1000.BTCUSDT",…]}},
 * routes data frames by the symbol at the end of their {@code topic}, and keeps the connection
 * alive with a text {@code {"op":"ping"}}. Depth 1000 is fixed: the REST snapshot's {@code u} only
 * stitches onto the {@code orderbook.1000} counter ({@code external-docs/bybit/bybit-api-contracts.md}
 * §7.1).
 *
 * <h3>Frame shapes, as delivered (contracts §4.3, §4.6, §5.2)</h3>
 * <pre>
 * {"topic":"orderbook.1000.BTCUSDT","ts":…,"type":"snapshot","data":{…},"cts":…}   spot data
 * {"topic":"orderbook.1000.BTCUSDT","type":"delta","ts":…,"data":{…},"cts":…}      linear data
 * {"success":true,"ret_msg":"subscribe","conn_id":"…","req_id":"0","op":"subscribe"}   spot ack
 * {"success":true,"ret_msg":"","conn_id":"…","req_id":"0","op":"subscribe"}            linear ack
 * {"success":true,"ret_msg":"pong","conn_id":"…","op":"ping"}                          pong
 * {"success":false,"ret_msg":"Invalid topic :[…]","conn_id":"…","req_id":"0","op":"subscribe"}
 * </pre>
 * The two categories order the data fields differently, but both lead with {@code topic}, so the
 * symbol sits at a fixed offset. A data frame in any other order is still routed by searching for
 * its topic. The pong echoes {@code op:"ping"}, not {@code "pong"}.
 *
 * <h3>Server limits checked at construction</h3>
 * Both break a subscription without any sign in the data, so a config that could break them fails
 * startup instead:
 * <ul>
 *   <li>A spot subscribe frame takes at most {@value #SPOT_MAX_ARGS} args. An 11th rejects the whole
 *       frame (§4.5).</li>
 *   <li>The {@code args} array must stay under {@value #ARGS_MAX_CHARS} chars. Over it, Bybit closes
 *       the connection with {@code 1009} and no error frame (§4.4), and core's reconnect would resend
 *       the same frame forever. The check assumes no symbol is longer than
 *       {@value #MAX_SYMBOL_LENGTH} chars (the longest seen is 19, {@code 1000000BABYDOGEUSDT}).</li>
 * </ul>
 *
 * <p>A subscribe frame is all-or-nothing (§4.3): a rejected one leaves every book in it
 * {@code PENDING} with no traffic. The WARN logged here and the health log's synced/tracked count
 * are the only signs.
 */
@Slf4j
public final class BybitStreamProtocol implements StreamProtocol {

    static final String PING = "{\"op\":\"ping\"}";

    /**
     * The subscribed {@code orderbook} depth, and so the {@code limit} of every REST snapshot: REST
     * {@code u} is on this topic's counter, and a shallower snapshot would leave the levels between
     * its depth and this one missing until they next change.
     */
    static final int DEPTH = 1000;

    /** {@code orderbook.{depth}.} — the topic up to its symbol. */
    static final String TOPIC_PREFIX = "orderbook." + DEPTH + ".";

    static final int SPOT_MAX_ARGS = 10;
    static final int ARGS_MAX_CHARS = 21_000;
    static final int MAX_SYMBOL_LENGTH = 32;

    private static final String TOPIC_LEAD = "{\"topic\":\"" + TOPIC_PREFIX;
    private static final String TOPIC_FIELD = "\"topic\":\"" + TOPIC_PREFIX;
    private static final String SUCCESS_LEAD = "{\"success\":";

    private static final String REJECTED = "\"success\":false";
    private static final String PONG = "\"ret_msg\":\"pong\"";
    private static final String SUBSCRIBE_OP = "\"op\":\"subscribe\"";

    private final Venue venue;
    private final VenueProperties props;

    /**
     * @throws IllegalArgumentException if {@code subscribe-chunk-size} could break a server limit:
     *         over {@value #SPOT_MAX_ARGS} on spot, or a chunk whose worst-case {@code args} array
     *         exceeds {@value #ARGS_MAX_CHARS} chars on either venue
     */
    public BybitStreamProtocol(Venue venue, VenueProperties props) {
        int chunkSize = props.subscribeChunkSize();
        if (venue.market() == Market.SPOT && chunkSize > SPOT_MAX_ARGS) {
            throw new IllegalArgumentException(venue + ": subscribe-chunk-size must be <= " + SPOT_MAX_ARGS
                    + " (a spot subscribe frame takes at most " + SPOT_MAX_ARGS + " args), got " + chunkSize);
        }
        int worstCase = argsLength(chunkSize, TOPIC_PREFIX.length() + MAX_SYMBOL_LENGTH);
        if (worstCase > ARGS_MAX_CHARS) {
            throw new IllegalArgumentException(venue + ": subscribe-chunk-size " + chunkSize
                    + " could serialize " + worstCase + " chars of args, over Bybit's " + ARGS_MAX_CHARS
                    + "-char limit (the server closes the connection with 1009)");
        }
        this.venue = venue;
        this.props = props;
    }

    /** The serialized length of {@code ["t1","t2",…]} holding {@code count} topics of {@code topicLength} chars. */
    static int argsLength(int count, int topicLength) {
        // Two brackets, two quotes per topic, and a comma between topics.
        return 2 + count * (topicLength + 2) + Math.max(0, count - 1);
    }

    @Override
    public String subscribeFrame(List<Instrument> chunk, int requestId) {
        StringBuilder args = new StringBuilder(2 + chunk.size() * (TOPIC_PREFIX.length() + 24));
        args.append('[');
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) args.append(',');
            args.append('"').append(TOPIC_PREFIX).append(chunk.get(i).nativeSymbol()).append('"');
        }
        args.append(']');
        if (args.length() > ARGS_MAX_CHARS) {
            // Only reachable with a symbol longer than MAX_SYMBOL_LENGTH. The frame is still sent:
            // the close it causes is at least visible, while dropping topics here would not be.
            log.error("[{}] Subscribe frame {} carries {} chars of args, over Bybit's {}-char limit: "
                            + "expect a 1009 close. Lower subscribe-chunk-size or raise MAX_SYMBOL_LENGTH",
                    venue, requestId, args.length(), ARGS_MAX_CHARS);
        }
        // req_id is echoed in the ack, so a rejection names the chunk it rejected.
        return "{\"req_id\":\"" + requestId + "\",\"op\":\"subscribe\",\"args\":" + args + '}';
    }

    @Override
    public String routingKey(Instrument instrument) {
        return instrument.nativeSymbol();
    }

    @Override
    public int route(String frame, SubscriptionIndex index) {
        int start;
        if (frame.startsWith(TOPIC_LEAD)) {
            start = TOPIC_LEAD.length();
        } else {
            // Control frames lead with "success" and never carry a topic, so only a data frame in
            // an unexpected field order pays for the search.
            int topic = frame.startsWith(SUCCESS_LEAD) ? -1 : frame.indexOf(TOPIC_FIELD);
            if (topic == -1) {
                onControlFrame(frame);
                return IGNORED;
            }
            start = topic + TOPIC_FIELD.length();
        }
        int end = frame.indexOf('"', start);
        if (end == -1) return IGNORED;
        return index.resolve(frame, start, end);
    }

    /** Acks arrive once per subscribe frame and pongs once per heartbeat; neither is per-push traffic. */
    private void onControlFrame(String frame) {
        if (frame.contains(REJECTED)) {
            // Every topic in the rejected frame stays unsubscribed; without this the only symptom
            // would be books that never sync.
            log.warn("[{}] Request rejected: {}", venue, frame);
        } else if (frame.contains(PONG)) {
            // Heartbeat reply.
        } else if (frame.contains(SUBSCRIBE_OP)) {
            // Not a barrier: on linear the snapshot can arrive before its ack (§4.3).
            log.debug("[{}] Subscribe acked: {}", venue, frame);
        } else {
            log.debug("[{}] Unrecognised frame: {}", venue, frame);
        }
    }

    @Override
    public Heartbeat heartbeat() {
        return new Heartbeat.TextPing(Duration.ofSeconds(props.heartbeatIntervalSeconds()), PING);
    }
}
