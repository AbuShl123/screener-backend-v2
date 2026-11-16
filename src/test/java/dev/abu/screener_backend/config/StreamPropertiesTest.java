package dev.abu.screener_backend.config;

import dev.abu.screener_backend.config.ExchangesProperties.StreamProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link StreamProperties} fails at startup on config that would otherwise misbehave at runtime. */
class StreamPropertiesTest {

    @Test
    @DisplayName("a non-positive subscribe chunk size is rejected")
    void badChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> TestVenueProps.stream().chunk(0).build());
    }

    @Test
    @DisplayName("a non-positive heartbeat interval is rejected")
    void badHeartbeat() {
        assertThrows(IllegalArgumentException.class, () -> TestVenueProps.stream().heartbeatSeconds(0).build());
    }
}
