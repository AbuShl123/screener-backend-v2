package dev.abu.screener_backend.feed.cluster;

import dev.abu.screener_backend.analysis.DefaultClassificationRule;
import dev.abu.screener_backend.analysis.cluster.OrderCluster;
import dev.abu.screener_backend.analysis.cluster.OrderClusterDetector;
import dev.abu.screener_backend.analysis.cluster.OrderClusterDetectorTest;
import dev.abu.screener_backend.config.ClusterDetectionProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ClusterChannel} over a real {@link ClusterFeedStore}, one tick per {@code drain()}. */
class ClusterChannelTest {

    private static final Instrument ARB = Instrument.of(0, Venue.BINANCE_SPOT, "ARBUSDT", "ARB", "USDT");
    private static final Instrument XYZ = Instrument.of(1, Venue.MEXC_FUTURES, "XYZ_USDT", "XYZ", "USDT");

    private final ClusterFeedStore store = new ClusterFeedStore();
    private final ClusterChannel channel = new ClusterChannel(store, props(true));

    private static ClusterDetectionProperties props(boolean delivery) {
        ClusterDetectionProperties p = OrderClusterDetectorTest.props();
        return new ClusterDetectionProperties(p.enabled(), delivery, p.throttleMillis(), p.maxDistance(),
                p.minLevels(), p.similarityTolerance(), p.gapFactor(), p.standoutRatio(), p.exitFactor(),
                p.minLifetimeMillis(), p.updateThreshold(), p.maxClustersPerSide(), p.sameClusterOverlap(),
                p.maxClusterLevels(), p.minTotalNotional());
    }

    private static OrderCluster cluster() {
        return new OrderCluster(0.07, 0.0702, 3, 336_000.123, 4_800_000, 0.03123456, 1_759_667_423_000L,
                new double[]{0.07, 0.0701, 0.0702}, new double[]{1_600_000, 1_600_000, 1_600_000});
    }

    private static UserWebSocketSession session() {
        return new UserWebSocketSession(null, UUID.randomUUID());
    }

    private List<String> updates() {
        List<String> out = new ArrayList<>();
        channel.collectUpdates(session(), out);
        return out;
    }

    private List<String> snapshot() {
        List<String> out = new ArrayList<>();
        channel.collectSnapshot(session(), out);
        return out;
    }

    @Test
    @DisplayName("an upsert is one CLUSTER envelope in contract shape: ladder as [price, qty], distance at 4 dp, notional at 2 dp")
    void bodyShape() {
        store.onClusters(ARB, List.of(cluster()), List.of());
        channel.drain();

        assertEquals(List.of("{\"type\":\"CLUSTER\",\"exchange\":\"BINANCE\",\"market\":\"SPOT\",\"symbol\":\"ARBUSDT\","
                + "\"data\":{\"bidClusters\":[{\"priceFrom\":0.07,\"priceTo\":0.0702,\"levelCount\":3,"
                + "\"totalNotional\":336000.12,\"totalQuantity\":4800000.0,\"distance\":0.0312,"
                + "\"firstSeenMillis\":1759667423000,"
                + "\"levels\":[[0.07,1600000.0],[0.0701,1600000.0],[0.0702,1600000.0]]}],"
                + "\"askClusters\":[]}}"), updates());
    }

    @Test
    @DisplayName("a removal is the same envelope with data null, and leaves the snapshot")
    void removal() {
        store.onClusters(ARB, List.of(cluster()), List.of());
        channel.drain();
        store.onClustersRemoved(ARB);
        channel.drain();

        assertEquals(List.of("{\"type\":\"CLUSTER\",\"exchange\":\"BINANCE\",\"market\":\"SPOT\",\"symbol\":\"ARBUSDT\",\"data\":null}"),
                updates());
        assertEquals(List.of(), snapshot());
    }

    @Test
    @DisplayName("several changes to one instrument inside a tick coalesce to the latest; a quiet tick sends nothing")
    void coalescing() {
        store.onClusters(ARB, List.of(cluster()), List.of());
        store.onClusters(ARB, List.of(), List.of(cluster()));
        channel.drain();

        List<String> bodies = updates();
        assertEquals(1, bodies.size());
        assertTrue(bodies.getFirst().contains("\"bidClusters\":[],\"askClusters\":[{"));

        channel.drain();
        assertEquals(List.of(), updates());
    }

    @Test
    @DisplayName("the snapshot has one CLUSTER entry per instrument with clusters, identical to the live upsert, none for others")
    void snapshotEntries() {
        store.onClusters(ARB, List.of(cluster()), List.of());
        store.onClusters(XYZ, List.of(), List.of(cluster()));
        store.onClustersRemoved(XYZ);
        channel.drain();
        String live = updates().stream().filter(b -> b.contains("ARBUSDT")).findFirst().orElseThrow();

        assertEquals(List.of(live), snapshot());
    }

    @Test
    @DisplayName("a cluster announced after the drain is in neither this tick's snapshot nor its updates: it arrives live next tick")
    void snapshotIsAsOfTheLastDrain() {
        channel.drain();
        store.onClusters(ARB, List.of(cluster()), List.of()); // after this tick's drain

        assertEquals(List.of(), snapshot());
        assertEquals(List.of(), updates());

        channel.drain();
        assertEquals(1, updates().size());
        assertEquals(updates(), snapshot());
    }

    @Test
    @DisplayName("an instrument marked dirty twice in one drain is delivered once")
    void oneBodyPerInstrumentPerTick() {
        Map<String, ClusterUpdate> out = new LinkedHashMap<>();
        store.onClusters(ARB, List.of(cluster()), List.of());
        store.drainChanged(out);
        store.onClustersRemoved(ARB);
        store.drainChanged(out); // same map, as if the iterator met the key again

        assertEquals(1, out.size());
        assertTrue(out.get(ARB.feedKey()).removed(), "the later read wins");
    }

    @Test
    @DisplayName("large and tiny numbers go out in Java's exponent notation, which is valid JSON")
    void exponentNotation() throws Exception {
        OrderCluster big = new OrderCluster(0.00007, 0.00008, 2, 2_240_000, 62_340_000, 0.00004, 1L,
                new double[]{0.00007, 0.00008}, new double[]{16_000_000, 46_340_000});
        store.onClusters(ARB, List.of(big), List.of());
        channel.drain();
        String body = updates().getFirst();

        assertTrue(body.contains("\"totalQuantity\":6.234E7"), body);
        assertTrue(body.contains("\"levels\":[[7.0E-5,1.6E7],[8.0E-5,4.634E7]]"), body);
        assertTrue(body.contains("\"distance\":0.0,"), "rounded to 4 dp: " + body);
        new tools.jackson.databind.ObjectMapper().readTree(body); // parses
    }

    @Test
    @DisplayName("with delivery-enabled false the store still tracks clusters, but the channel sends nothing")
    void shadowMode() {
        ClusterChannel shadow = new ClusterChannel(store, props(false));
        store.onClusters(ARB, List.of(cluster()), List.of());
        shadow.drain();

        List<String> out = new ArrayList<>();
        shadow.collectUpdates(session(), out);
        shadow.collectSnapshot(session(), out);
        assertEquals(List.of(), out);
        assertEquals(1, store.current().size(), "still visible to monitoring");
    }

    @Test
    @DisplayName("end to end: a detected ARB ladder reaches a session as a CLUSTER upsert in the next tick, its removal as data null")
    void detectorToSession() {
        AtomicLong clock = new AtomicLong(0);
        OrderClusterDetector detector = new OrderClusterDetector(OrderClusterDetectorTest.props(),
                new DefaultClassificationRule(), store, new PipelineMetrics(), clock::get);
        OrderBook ob = new OrderBook(0.1);
        for (int t = 760; t >= 741; t--) ob.applyLevel(true, t / 10_000.0, 2_000 / (t / 10_000.0), 0);
        for (int t = 740; t >= 700; t--) ob.applyLevel(true, t / 10_000.0, 110_000 / (t / 10_000.0), 0);
        for (int t = 761; t <= 800; t++) ob.applyLevel(false, t / 10_000.0, 2_000 / (t / 10_000.0), 0);
        ob.computeDistance();
        ob.markSynced();

        for (int s = 0; s <= 3; s++) {
            clock.set(s * 1000L);
            detector.process(ARB, ob);
        }
        channel.drain();
        List<String> bodies = updates();
        assertEquals(1, bodies.size());
        assertTrue(bodies.getFirst().contains("\"priceFrom\":0.07,\"priceTo\":0.074,\"levelCount\":41"));

        ob.markPending();
        detector.process(ARB, ob);
        channel.drain();
        assertEquals(List.of("{\"type\":\"CLUSTER\",\"exchange\":\"BINANCE\",\"market\":\"SPOT\",\"symbol\":\"ARBUSDT\",\"data\":null}"),
                updates());
    }
}
