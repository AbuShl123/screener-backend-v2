package dev.abu.screener_backend.marketdata.core.stream;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEventPublisher;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Java-WebSocket behavior that lets {@link StreamConnection} publish a binary frame
 * without copying it: every frame arrives in a <b>fresh heap buffer</b>, never reused for a later
 * frame. If a library upgrade starts reusing buffers, a frame still waiting in the ring would be
 * overwritten by the next one, and books would be corrupted without any error. This test fails
 * first.
 *
 * <p>Runs a real loopback server and a real {@link StreamConnection}. The server sends its frames
 * back to back, so they usually share one socket read on the client: the case where a reused
 * decode buffer would show.
 */
class JavaWebSocketBufferOwnershipTest {

    private static final int FRAMES = 3;

    private WebSocketServer server;
    private StreamConnection connection;
    private ScheduledExecutorService scheduler;

    /** Routes every binary frame to the one instrument. */
    private static final class BinaryProtocol implements StreamProtocol {
        @Override public String subscribeFrame(List<Instrument> chunk, int requestId) { return "sub"; }
        @Override public String routingKey(Instrument instrument) { return instrument.nativeSymbol(); }
        @Override public int route(String frame, SubscriptionIndex index) { return IGNORED; }
        @Override public int route(ByteBuffer frame, SubscriptionIndex index) { return 0; }
        @Override public Heartbeat heartbeat() { return new Heartbeat.ProtocolPing(Duration.ofHours(1)); }
    }

    private static final class CapturingPublisher implements DepthEventPublisher {
        final List<ByteBuffer> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch received = new CountDownLatch(FRAMES);

        @Override
        public void publishFrame(int instrumentId, ByteBuffer payload) {
            frames.add(payload);
            received.countDown();
        }

        @Override public void publishFrame(int instrumentId, String payload) { }
        @Override public void publishSnapshot(int instrumentId, String payload) { }
        @Override public void publishSnapshotFailure(int instrumentId) { }
    }

    /** Distinct sizes and contents, so an overwrite by any later frame is visible. */
    private static byte[] frame(int i) {
        byte[] bytes = new byte[200 + 50 * i];
        Arrays.fill(bytes, (byte) ('A' + i));
        return bytes;
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (connection != null) connection.shutdown();
        if (server != null) server.stop(1000);
        if (scheduler != null) scheduler.shutdownNow();
    }

    @Test
    @DisplayName("consecutive binary frames arrive in distinct heap buffers that keep their bytes after later frames")
    void framesAreNotReused() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        server = new WebSocketServer(new InetSocketAddress("127.0.0.1", 0)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                for (int i = 0; i < FRAMES; i++) conn.send(frame(i));
            }

            @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) { }
            @Override public void onMessage(WebSocket conn, String message) { }
            @Override public void onError(WebSocket conn, Exception ex) { }
            @Override public void onStart() { started.countDown(); }
        };
        server.setReuseAddr(true);
        server.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "server did not start");

        String url = "ws://127.0.0.1:" + server.getPort();
        RestProperties rest = new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));
        VenueProperties props = new VenueProperties(url, rest, "{symbol}@depth", 1024, 1, 1, 400, 120, null);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        CapturingPublisher publisher = new CapturingPublisher();
        connection = new StreamConnection(URI.create(url), Venue.BINANCE_SPOT,
                List.of(Instrument.of(0, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT")),
                new BinaryProtocol(), publisher, new PipelineMetrics(), scheduler, props,
                new WebSocketProperties(100, 1000));

        assertTrue(connection.connectBlocking(5, TimeUnit.SECONDS), "client did not connect");
        assertTrue(publisher.received.await(5, TimeUnit.SECONDS), "frames did not arrive");

        assertEquals(FRAMES, publisher.frames.size());
        for (int i = 0; i < FRAMES; i++) {
            ByteBuffer buf = publisher.frames.get(i);
            assertTrue(buf.hasArray(), "frame " + i + " is not a heap buffer");
            byte[] bytes = new byte[buf.remaining()];
            buf.get(buf.position(), bytes);
            assertArrayEquals(frame(i), bytes, "frame " + i + " was overwritten");
            for (int j = 0; j < i; j++) {
                ByteBuffer earlier = publisher.frames.get(j);
                assertNotSame(earlier, buf);
                assertNotSame(earlier.array(), buf.array(), "frames " + j + " and " + i + " share a backing array");
            }
        }
    }
}
