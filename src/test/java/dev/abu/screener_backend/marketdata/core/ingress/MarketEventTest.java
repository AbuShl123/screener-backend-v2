package dev.abu.screener_backend.marketdata.core.ingress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarketEventTest {

    @Test
    @DisplayName("clear() resets every field, stream included")
    void clearResetsEveryField() {
        MarketEvent event = new MarketEvent();
        event.source = EventSource.WS_MSG;
        event.stream = StreamKind.DEPTH;
        event.instrumentId = 42;
        event.rawJson = "{}";
        event.rawBytes = ByteBuffer.allocate(1);

        event.clear();

        assertNull(event.source);
        assertNull(event.stream);
        assertEquals(-1, event.instrumentId);
        assertNull(event.rawJson);
        assertNull(event.rawBytes);
    }
}
