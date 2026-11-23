package dev.abu.screener_backend.analysis.cluster;

import dev.abu.screener_backend.marketdata.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The detector's sink until delivery exists: logs announced clusters at {@code debug} and nothing
 * else. Cluster changes are rare (throttled, lifetime-gated, update-thresholded), so this is not a
 * per-message log. BE-2 replaces this bean with the cluster feed store.
 */
@Slf4j
@Component
public class LoggingClusterSink implements ClusterSink {

    @Override
    public void onClusters(Instrument instrument, List<OrderCluster> bidClusters, List<OrderCluster> askClusters) {
        if (log.isDebugEnabled()) {
            log.debug("clusters {}: bids={} asks={}", instrument.logName(), describe(bidClusters), describe(askClusters));
        }
    }

    @Override
    public void onClustersRemoved(Instrument instrument) {
        log.debug("clusters {}: removed", instrument.logName());
    }

    private static String describe(List<OrderCluster> clusters) {
        StringBuilder sb = new StringBuilder("[");
        for (OrderCluster c : clusters) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(c.priceFrom()).append('-').append(c.priceTo())
                    .append(" x").append(c.levelCount())
                    .append(" $").append(Math.round(c.totalNotional()))
                    .append(" d=").append(c.distance());
        }
        return sb.append(']').toString();
    }
}
