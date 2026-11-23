package dev.abu.screener_backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterDetectionPropertiesTest {

    private static Map<String, String> yaml() {
        Map<String, String> m = new HashMap<>();
        m.put("screener.clusters.enabled", "true");
        m.put("screener.clusters.delivery-enabled", "false");
        m.put("screener.clusters.throttle-millis", "750");
        m.put("screener.clusters.max-distance", "0.05");
        m.put("screener.clusters.min-levels", "8");
        m.put("screener.clusters.similarity-tolerance", "0.4");
        m.put("screener.clusters.gap-factor", "3.0");
        m.put("screener.clusters.standout-ratio", "3.0");
        m.put("screener.clusters.exit-factor", "0.8");
        m.put("screener.clusters.min-lifetime-millis", "3000");
        m.put("screener.clusters.update-threshold", "0.15");
        m.put("screener.clusters.max-clusters-per-side", "3");
        m.put("screener.clusters.min-total-notional.high-liquidity", "2000000");
        m.put("screener.clusters.min-total-notional.default", "300000");
        return m;
    }

    private static ClusterDetectionProperties bind(Map<String, String> m) {
        return new Binder(new MapConfigurationPropertySource(m))
                .bind("screener.clusters", ClusterDetectionProperties.class).get();
    }

    @Test
    @DisplayName("binds every key, including min-total-notional.default (a Java keyword)")
    void bindsYaml() {
        ClusterDetectionProperties p = bind(yaml());

        assertTrue(p.enabled());
        assertFalse(p.deliveryEnabled());
        assertEquals(750, p.throttleMillis());
        assertEquals(8, p.minLevels());
        assertEquals(3, p.maxClustersPerSide());
        assertEquals(2_000_000, p.minTotalNotional().forClass(true));
        assertEquals(300_000, p.minTotalNotional().forClass(false));
    }

    @Test
    @DisplayName("rejects an exit factor above 1 and fewer than 2 levels")
    void validates() {
        Map<String, String> m = yaml();
        m.put("screener.clusters.exit-factor", "1.2");
        assertThrows(BindException.class, () -> bind(m));

        Map<String, String> n = yaml();
        n.put("screener.clusters.min-levels", "1");
        assertThrows(BindException.class, () -> bind(n));
    }
}
