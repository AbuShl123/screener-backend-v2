package dev.abu.screener_backend.exchange.stream;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.ingress.DepthEventPublisher;
import dev.abu.screener_backend.exchange.spi.Heartbeat;
import dev.abu.screener_backend.exchange.spi.StreamProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The venue-agnostic parts of {@link StreamConnection}: subscribe chunking and frame dispatch. No
 * socket is ever opened — the {@code WebSocketClient} constructor does not connect.
 */
class StreamConnectionTest {

    /** Frames name their chunk as {@code "<requestId>:<chunkSize>"}; routing answers a fixed value. */
    private static final class StubProtocol implements StreamProtocol {
        int routeResult;

        @Override
        public String subscribeFrame(List<Instrument> chunk, int requestId) {
            return requestId + ":" + chunk.size();
        }

        @Override
        public String routingKey(Instrument instrument) {
            return instrument.nativeSymbol();
        }

        @Override
        public int route(String frame, SubscriptionIndex index) {
            return routeResult;
        }

        @Override
        public Heartbeat heartbeat() {
            return new Heartbeat.ProtocolPing(Duration.ofSeconds(1));
        }
    }

    private static final class RecordingPublisher implements DepthEventPublisher {
        final List<Integer> ids = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();

        @Override
        public void publishFrame(int instrumentId, String payload) {
            ids.add(instrumentId);
            payloads.add(payload);
        }

        @Override
        public void publishSnapshot(int instrumentId, String payload) {
            throw new AssertionError("the transport never publishes snapshots");
        }
    }

    private static List<Instrument> instruments(int n) {
        List<Instrument> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(Instrument.of(i, Venue.BINANCE_SPOT, "SYM" + i + "USDT", "SYM" + i, "USDT"));
        return out;
    }

    private static StreamConnection connection(StreamProtocol protocol, DepthEventPublisher publisher) {
        RestProperties rest = new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));
        VenueProperties props = new VenueProperties("ws://localhost:1", rest, "{symbol}@depth",
                1024, 1000, 1, 1, 400, 120);
        // The reconnect scheduler is only touched from onOpen/onClose, which these tests never reach.
        return new StreamConnection(URI.create(props.streamUrl()), Venue.BINANCE_SPOT, instruments(3),
                protocol, publisher, null, props, new WebSocketProperties(100, 1000));
    }

    @Test
    @DisplayName("no instruments → no subscribe frames")
    void noFrames() {
        assertTrue(StreamConnection.subscribeFrames(new StubProtocol(), List.of(), 2).isEmpty());
    }

    @Test
    @DisplayName("5 instruments at chunk size 2 → chunks [2, 2, 1] with request ids 0, 1, 2")
    void chunking() {
        assertEquals(List.of("0:2", "1:2", "2:1"),
                StreamConnection.subscribeFrames(new StubProtocol(), instruments(5), 2));
    }

    @Test
    @DisplayName("a routed frame is published once, as the same String instance")
    void routedFramePublished() {
        StubProtocol protocol = new StubProtocol();
        protocol.routeResult = 2;
        RecordingPublisher publisher = new RecordingPublisher();
        String frame = new String("{\"e\":\"depthUpdate\"}");

        connection(protocol, publisher).onMessage(frame);

        assertEquals(List.of(2), publisher.ids);
        assertSame(frame, publisher.payloads.getFirst());
    }

    @Test
    @DisplayName("IGNORED and UNKNOWN frames are not published")
    void nonDataFramesDropped() {
        StubProtocol protocol = new StubProtocol();
        RecordingPublisher publisher = new RecordingPublisher();
        StreamConnection connection = connection(protocol, publisher);

        protocol.routeResult = StreamProtocol.IGNORED;
        connection.onMessage("{\"result\":null,\"id\":0}");
        protocol.routeResult = StreamProtocol.UNKNOWN;
        connection.onMessage("{\"e\":\"depthUpdate\",\"s\":\"NOPE\"}");

        assertTrue(publisher.ids.isEmpty());
    }
}
