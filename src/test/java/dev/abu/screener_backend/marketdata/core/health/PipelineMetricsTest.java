package dev.abu.screener_backend.marketdata.core.health;

import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PipelineMetricsTest {

    @Test
    @DisplayName("frames are counted per stream key and do not leak into any other key")
    void framesPerStream() {
        PipelineMetrics metrics = new PipelineMetrics();
        StreamKey counted = new StreamKey(Venue.MEXC_SPOT, StreamKind.DEPTH);

        metrics.recordFrame(counted);
        metrics.recordFrame(counted);
        metrics.recordFrame(counted);

        for (Venue venue : Venue.values()) {
            for (StreamKind kind : StreamKind.values()) {
                StreamKey key = new StreamKey(venue, kind);
                assertEquals(key.equals(counted) ? 3 : 0, metrics.frames(key), key.logName());
            }
        }
    }
}
