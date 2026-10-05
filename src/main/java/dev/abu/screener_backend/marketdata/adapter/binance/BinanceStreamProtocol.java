package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Binance's depth-stream wire protocol, shared by spot and futures (one instance per venue).
 *
 * <p>Subscribes with {@code {"method":"SUBSCRIBE","params":[…],"id":N}}, routes depth frames by
 * their {@code "s"} field (the upper-case native symbol), and keeps the connection alive with a
 * WebSocket control-frame PING.
 */
@Slf4j
public final class BinanceStreamProtocol implements StreamProtocol {

    private static final String SYMBOL_FIELD = "\"s\":\"";

    private final Venue venue;
    private final VenueProperties props;

    public BinanceStreamProtocol(Venue venue, VenueProperties props) {
        this.venue = venue;
        this.props = props;
    }

    @Override
    public String subscribeFrame(List<Instrument> chunk, int requestId) {
        StringBuilder sb = new StringBuilder(32 + chunk.size() * 32);
        sb.append("{\"method\":\"SUBSCRIBE\",\"params\":[");
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) sb.append(',');
            String symbol = chunk.get(i).nativeSymbol().toLowerCase(Locale.ROOT);
            sb.append('"').append(props.streamTopic(symbol)).append('"');
        }
        return sb.append("],\"id\":").append(requestId).append('}').toString();
    }

    @Override
    public String routingKey(Instrument instrument) {
        return instrument.nativeSymbol();
    }

    @Override
    public int route(String frame, SubscriptionIndex index) {
        if (frame.length() <= 4) return IGNORED;
        // O(1) discrimination on the first key: {"result":…} is a SUBSCRIBE ack, {"error":…} a
        // rejected request, and every depth frame starts {"e":"depthUpdate" — charAt(3) is what
        // separates "e" from "error".
        char c2 = frame.charAt(2);
        if (c2 == 'r') {
            return IGNORED;
        }
        if (c2 == 'e' && frame.charAt(3) == 'r') {
            // An invalid stream name or too many streams. Without this the only symptom would be
            // books that never sync.
            log.warn("[{}] Stream error frame: {}", venue, frame);
            return IGNORED;
        }
        int sPos = frame.indexOf(SYMBOL_FIELD);
        if (sPos == -1) return IGNORED;
        int start = sPos + SYMBOL_FIELD.length();
        int end = frame.indexOf('"', start);
        if (end == -1) return IGNORED;
        return index.resolve(frame, start, end);
    }

    @Override
    public Heartbeat heartbeat() {
        return new Heartbeat.ProtocolPing(Duration.ofSeconds(props.heartbeatIntervalSeconds()));
    }
}
