package dev.abu.screener_backend.marketdata.core.stream;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEventPublisher;
import dev.abu.screener_backend.marketdata.core.stream.StreamConnection;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The venue-agnostic parts of {@link StreamConnection}: subscribe chunking, frame dispatch and frame
 * counting. No
 * socket is ever opened — the {@code WebSocketClient} constructor does not connect.
 */
class StreamConnectionTest {

    /**
     * Frames name their chunk as {@code "<requestId>:<chunkSize>"}; text routing answers a fixed
     * value. Binary routing is the interface default, as for every text venue.
     */
    private static class StubProtocol implements StreamProtocol {
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

    /** A binary venue: binary routing answers a fixed value too. */
    private static final class BinaryStubProtocol extends StubProtocol {
        int binaryRouteResult;

        @Override
        public int route(ByteBuffer frame, SubscriptionIndex index) {
            return binaryRouteResult;
        }
    }

    private static final class RecordingPublisher implements DepthEventPublisher {
        final List<Integer> ids = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();
        final List<ByteBuffer> binaryPayloads = new ArrayList<>();

        @Override
        public void publishFrame(int instrumentId, String payload) {
            ids.add(instrumentId);
            payloads.add(payload);
        }

        @Override
        public void publishFrame(int instrumentId, ByteBuffer payload) {
            ids.add(instrumentId);
            binaryPayloads.add(payload);
        }

        @Override
        public void publishSnapshot(int instrumentId, String payload) {
            throw new AssertionError("the transport never publishes snapshots");
        }

        @Override
        public void publishSnapshotFailure(int instrumentId) {
            throw new AssertionError("the transport never publishes snapshot failures");
        }
    }

    private static List<Instrument> instruments(int n) {
        List<Instrument> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(Instrument.of(i, Venue.BINANCE_SPOT, "SYM" + i + "USDT", "SYM" + i, "USDT"));
        return out;
    }

    private static StreamConnection connection(StreamProtocol protocol, DepthEventPublisher publisher) {
        return connection(protocol, publisher, new PipelineMetrics());
    }

    private static StreamConnection connection(StreamProtocol protocol, DepthEventPublisher publisher,
                                               PipelineMetrics metrics) {
        RestProperties rest = new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));
        VenueProperties props = new VenueProperties("ws://localhost:1", rest, 1024, 1, 1, 400, 120, null);
        // The reconnect scheduler is only touched from onOpen/onClose, which these tests never reach.
        return new StreamConnection(URI.create(props.streamUrl()), Venue.BINANCE_SPOT, instruments(3),
                protocol, publisher, metrics, null, props, new WebSocketProperties(100, 1000));
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

    @Test
    @DisplayName("routed frames are counted against the connection's venue; IGNORED and UNKNOWN are not")
    void routedFramesCounted() {
        StubProtocol protocol = new StubProtocol();
        PipelineMetrics metrics = new PipelineMetrics();
        StreamConnection connection = connection(protocol, new RecordingPublisher(), metrics);

        protocol.routeResult = 1;
        connection.onMessage("{\"e\":\"depthUpdate\"}");
        connection.onMessage("{\"e\":\"depthUpdate\"}");
        protocol.routeResult = StreamProtocol.IGNORED;
        connection.onMessage("{\"result\":null,\"id\":0}");
        protocol.routeResult = StreamProtocol.UNKNOWN;
        connection.onMessage("{\"e\":\"depthUpdate\",\"s\":\"NOPE\"}");

        assertEquals(2, metrics.frames(Venue.BINANCE_SPOT));
        assertEquals(0, metrics.frames(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("a text-only protocol ignores binary frames: never published, never counted, never thrown")
    void binaryFrameDroppedByTextProtocol() {
        PipelineMetrics metrics = new PipelineMetrics();
        RecordingPublisher publisher = new RecordingPublisher();
        StreamConnection connection = connection(new StubProtocol(), publisher, metrics);

        connection.onMessage(ByteBuffer.wrap(new byte[]{0x1f, (byte) 0x8b, 0x08}));
        connection.onMessage(ByteBuffer.wrap(new byte[]{0x1f, (byte) 0x8b, 0x08}));

        assertTrue(publisher.ids.isEmpty());
        assertEquals(0, metrics.frames(Venue.BINANCE_SPOT));
    }

    @Test
    @DisplayName("a routed binary frame is published once and counted, as the same buffer with position and limit untouched")
    void routedBinaryFramePublished() {
        BinaryStubProtocol protocol = new BinaryStubProtocol();
        protocol.binaryRouteResult = 2;
        PipelineMetrics metrics = new PipelineMetrics();
        RecordingPublisher publisher = new RecordingPublisher();
        ByteBuffer frame = ByteBuffer.wrap(new byte[]{0x0a, 0x03, 'a', 'b', 'c', 0x1a}, 1, 4);

        connection(protocol, publisher, metrics).onMessage(frame);

        assertEquals(List.of(2), publisher.ids);
        assertSame(frame, publisher.binaryPayloads.getFirst());
        assertEquals(1, frame.position());
        assertEquals(5, frame.limit());
        assertEquals(1, metrics.frames(Venue.BINANCE_SPOT));
        assertTrue(publisher.payloads.isEmpty());
    }

    @Test
    @DisplayName("IGNORED and UNKNOWN binary frames are not published or counted")
    void nonDataBinaryFramesDropped() {
        BinaryStubProtocol protocol = new BinaryStubProtocol();
        PipelineMetrics metrics = new PipelineMetrics();
        RecordingPublisher publisher = new RecordingPublisher();
        StreamConnection connection = connection(protocol, publisher, metrics);

        protocol.binaryRouteResult = StreamProtocol.IGNORED;
        connection.onMessage(ByteBuffer.wrap(new byte[]{0x08, 0x01}));
        protocol.binaryRouteResult = StreamProtocol.UNKNOWN;
        connection.onMessage(ByteBuffer.wrap("\n\bNOPEUSDT".getBytes(StandardCharsets.UTF_8)));

        assertTrue(publisher.ids.isEmpty());
        assertEquals(0, metrics.frames(Venue.BINANCE_SPOT));
    }
}
