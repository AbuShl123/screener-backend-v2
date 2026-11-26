package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.analysis.cluster.OrderCluster;
import dev.abu.screener_backend.feed.cluster.ClusterFeedStore;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.monitoring.MonitoringController.ClusterEntryView;
import dev.abu.screener_backend.monitoring.MonitoringController.ClusterView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code /api/monitoring/clusters}. */
class MonitoringControllerClustersTest {

    private static final Instrument ARB = Instrument.of(0, Venue.BINANCE_SPOT, "ARBUSDT", "ARB", "USDT");
    private static final Instrument XYZ = Instrument.of(1, Venue.MEXC_FUTURES, "XYZ_USDT", "XYZ", "USDT");

    private final ClusterFeedStore store = new ClusterFeedStore();
    private final MonitoringController controller = new MonitoringController(null, null, null, null, null, store, null);

    private static OrderCluster cluster(double total) {
        return new OrderCluster(1.0, 1.1, 2, total, 10, 0.02, 1_000L,
                new double[]{1.0, 1.1}, new double[]{4, 6});
    }

    @Test
    @DisplayName("lists current clusters by venue and native symbol, largest first, with readable time and the ladder")
    void listsCurrentClusters() {
        store.onClusters(ARB, List.of(cluster(400_000)), List.of());
        store.onClusters(XYZ, List.of(), List.of(cluster(900_000)));

        List<ClusterEntryView> views = controller.getClusters(null, null);

        assertEquals(2, views.size());
        assertEquals(Venue.MEXC_FUTURES, views.get(0).venue());
        assertEquals("XYZ_USDT", views.get(0).symbol());
        assertEquals(900_000, views.get(0).largestNotional());
        ClusterView c = views.get(1).bidClusters().getFirst();
        assertEquals(Instant.ofEpochMilli(1_000L), c.firstSeen());
        assertArrayEquals(new double[]{1.1, 6}, c.levels().get(1));
    }

    @Test
    @DisplayName("filters by venue and case-insensitive symbol; a removed instrument is gone")
    void filtersAndRemoval() {
        store.onClusters(ARB, List.of(cluster(400_000)), List.of());
        store.onClusters(XYZ, List.of(), List.of(cluster(900_000)));

        assertEquals(1, controller.getClusters(Venue.BINANCE_SPOT, null).size());
        assertEquals("XYZ_USDT", controller.getClusters(null, " xyz_usdt ").getFirst().symbol());

        store.onClustersRemoved(XYZ);
        assertEquals(List.of("ARBUSDT"), controller.getClusters(null, null).stream().map(ClusterEntryView::symbol).toList());
    }
}
