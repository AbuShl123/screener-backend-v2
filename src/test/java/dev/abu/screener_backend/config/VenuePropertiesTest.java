package dev.abu.screener_backend.config;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link VenueProperties} fails at startup on config that would otherwise misbehave at runtime. */
class VenuePropertiesTest {

    private static final RestProperties REST =
            new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));

    private static VenueProperties props(String topic, int chunkSize, int heartbeatSeconds) {
        return new VenueProperties("wss://x", REST, topic, 1024, 1, 1, chunkSize, heartbeatSeconds, null);
    }

    @Test
    @DisplayName("streamTopic renders the template for one symbol, casing untouched")
    void rendersTopic() {
        assertEquals("btcusdt@depth", props("{symbol}@depth", 400, 120).streamTopic("btcusdt"));
        assertEquals("orderbook.50.BTCUSDT", props("orderbook.50.{symbol}", 400, 120).streamTopic("BTCUSDT"));
    }

    @Test
    @DisplayName("a stream-topic without {symbol} is rejected — e.g. the old \"@depth\" suffix form")
    void topicWithoutPlaceholder() {
        assertThrows(IllegalArgumentException.class, () -> props("@depth", 400, 120));
        assertThrows(IllegalArgumentException.class, () -> props(null, 400, 120));
    }

    @Test
    @DisplayName("a non-positive subscribe chunk size is rejected")
    void badChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> props("{symbol}@depth", 0, 120));
    }

    @Test
    @DisplayName("a non-positive heartbeat interval is rejected")
    void badHeartbeat() {
        assertThrows(IllegalArgumentException.class, () -> props("{symbol}@depth", 400, 0));
    }

    private static VenueProperties withVisibleDistance(Double maxVisibleDistance) {
        return new VenueProperties("wss://x", REST, "{symbol}", 1024, 1, 1, 400, 120, maxVisibleDistance);
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
}
