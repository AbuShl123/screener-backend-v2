package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.analysis.filter.CompositeLevelFilter;
import dev.abu.screener_backend.analysis.filter.LevelFilter;
import dev.abu.screener_backend.analysis.filter.MinAgeFilter;
import dev.abu.screener_backend.analysis.filter.MmFingerprintFilter;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.FingerprintProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.LevelFilterProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link VenueClassification#of} turns a venue's config block into its cap and level filter. */
class VenueClassificationTest {

    private static final RestProperties REST =
            new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));
    private static final FingerprintProperties FINGERPRINT = new FingerprintProperties(0.02, 0.001, 0.01, 0.25);

    private static VenueClassification of(Double maxVisibleDistance, LevelFilterProperties levelFilter) {
        return VenueClassification.of(new VenueProperties("wss://x", REST, "{symbol}", 300, 1, 8, 1, 15,
                maxVisibleDistance, levelFilter));
    }

    @Test
    @DisplayName("no cap and no level-filter block: no cap, ACCEPT_ALL")
    void noBlocks() {
        VenueClassification c = of(null, null);
        assertEquals(Double.POSITIVE_INFINITY, c.maxVisibleDistance());
        assertSame(LevelFilter.ACCEPT_ALL, c.filter());
    }

    @Test
    @DisplayName("an empty level-filter block filters nothing")
    void emptyLevelFilter() {
        assertSame(LevelFilter.ACCEPT_ALL, of(0.01, new LevelFilterProperties(null, null)).filter());
    }

    @Test
    @DisplayName("each filter alone is used as is")
    void singleFilter() {
        VenueClassification age = of(0.01, new LevelFilterProperties(Duration.ofSeconds(30), null));
        assertEquals(0.01, age.maxVisibleDistance());
        assertEquals(new MinAgeFilter(30_000), age.filter());

        assertEquals(new MmFingerprintFilter(0.02, 0.001, 0.01, 0.25),
                of(0.01, new LevelFilterProperties(null, FINGERPRINT)).filter());
    }

    @Test
    @DisplayName("both filters compose, age first")
    void bothFilters() {
        LevelFilter filter = of(0.01, new LevelFilterProperties(Duration.ofSeconds(30), FINGERPRINT)).filter();
        assertInstanceOf(CompositeLevelFilter.class, filter);
        assertEquals("allOf[MinAgeFilter[minAgeMillis=30000], MmFingerprintFilter[twinMaxDistance=0.02, "
                + "twinQuantityTolerance=0.001, mirrorNotionalTolerance=0.01, mirrorDistanceTolerance=0.25]]",
                filter.toString());
    }
}
