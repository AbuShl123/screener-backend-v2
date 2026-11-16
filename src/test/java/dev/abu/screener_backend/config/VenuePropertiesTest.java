package dev.abu.screener_backend.config;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link VenueProperties} fails at startup on config that would otherwise misbehave at runtime. */
class VenuePropertiesTest {

    private static VenueProperties withVisibleDistance(Double maxVisibleDistance) {
        return TestVenueProps.venue().maxVisibleDistance(maxVisibleDistance).build();
    }

    @Test
    @DisplayName("max-visible-distance is optional; when set it must be positive and finite")
    void maxVisibleDistance() {
        assertNull(withVisibleDistance(null).maxVisibleDistance());
        assertEquals(0.01, withVisibleDistance(0.01).maxVisibleDistance());
        assertThrows(IllegalArgumentException.class, () -> withVisibleDistance(0.0));
        assertThrows(IllegalArgumentException.class, () -> withVisibleDistance(-0.01));
        assertThrows(IllegalArgumentException.class, () -> withVisibleDistance(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> withVisibleDistance(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("a missing streams block binds as an empty map, not null")
    void missingStreamsIsEmpty() {
        VenueProperties props = new VenueProperties(true, TestVenueProps.REST, null, null);

        assertTrue(props.streams().isEmpty());
    }
}
