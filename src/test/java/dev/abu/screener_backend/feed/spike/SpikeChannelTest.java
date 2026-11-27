package dev.abu.screener_backend.feed.spike;

import dev.abu.screener_backend.config.SpikeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.spike.SpikeAlert;
import dev.abu.screener_backend.marketdata.core.spike.SpikeAlertStore;
import dev.abu.screener_backend.marketdata.core.spike.SpikeKind;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SpikeChannel} driven directly, one tick per {@code drain()}, against a real store. */
class SpikeChannelTest {

    private static final Instrument PEPE = Instrument.of(0, Venue.BINANCE_FUTURES, "1000PEPEUSDT", "1000PEPE", "USDT");
    private static final long T = 1_795_689_455_628L;

    private static SpikeAlertProperties props(boolean delivery, int replay) {
        return new SpikeAlertProperties(true, delivery, 10, replay,
                new SpikeAlertProperties.Price(5, 5, 1), new SpikeAlertProperties.Volume(5, 200, 70_000));
    }

    private final SpikeAlertProperties props = props(true, 2);
    private final SpikeAlertStore store = new SpikeAlertStore(props);
    private final SpikeChannel channel = new SpikeChannel(store, props);

    @Test
    @DisplayName("a pump is one PRICE_SPIKE envelope: direction UP, pct at 2 dp, from/to prices, window in seconds")
    void priceBody() {
        store.add(new SpikeAlert(PEPE, SpikeKind.PRICE, 6.3412, 0.01012, 0.01076, 5, T, T + 50));
        channel.drain();

        assertEquals(List.of("{\"type\":\"PRICE_SPIKE\",\"exchange\":\"BINANCE\",\"market\":\"FUTURES\","
                        + "\"symbol\":\"1000PEPEUSDT\",\"data\":{\"id\":\"BINANCE_FUTURES:1000PEPEUSDT:PRICE:" + T + "\","
                        + "\"time\":" + T + ",\"direction\":\"UP\",\"pct\":6.34,\"fromPrice\":0.01012,"
                        + "\"toPrice\":0.01076,\"windowSec\":300}}"),
                updates());
    }

    @Test
    @DisplayName("a dump is DOWN with a negative pct")
    void dumpBody() {
        store.add(new SpikeAlert(PEPE, SpikeKind.PRICE, -7.5, 100, 92.5, 5, T, T));
        channel.drain();
        String body = updates().getFirst();
        assertTrue(body.contains("\"direction\":\"DOWN\",\"pct\":-7.5,\"fromPrice\":100.0,\"toPrice\":92.5"), body);
    }

    @Test
    @DisplayName("a volume spike is one VOLUME_SPIKE envelope; pct is null when the previous window traded nothing")
    void volumeBody() {
        store.add(new SpikeAlert(PEPE, SpikeKind.VOLUME, 215.004, 40_000, 126_001.556, 5, T, T));
        store.add(new SpikeAlert(PEPE, SpikeKind.VOLUME, Double.POSITIVE_INFINITY, 0, 90_000, 5, T + 60_000, T));
        channel.drain();

        List<String> bodies = updates();
        assertEquals("{\"type\":\"VOLUME_SPIKE\",\"exchange\":\"BINANCE\",\"market\":\"FUTURES\","
                + "\"symbol\":\"1000PEPEUSDT\",\"data\":{\"id\":\"BINANCE_FUTURES:1000PEPEUSDT:VOLUME:" + T + "\","
                + "\"time\":" + T + ",\"pct\":215.0,\"prevVolume\":40000.0,\"volume\":126001.56,\"windowSec\":300}}",
                bodies.get(0));
        assertTrue(bodies.get(1).contains("\"pct\":null,\"prevVolume\":0.0,\"volume\":90000.0"), bodies.get(1));
    }

    @Test
    @DisplayName("each alert is sent live once; the snapshot replays the last replay-size alerts, oldest first")
    void liveOnceAndReplay() {
        store.add(price(1));
        channel.drain();
        assertEquals(1, updates().size());

        channel.drain();
        assertTrue(updates().isEmpty());

        store.add(price(2));
        store.add(price(3));
        channel.drain();
        List<String> replay = snapshot();
        assertEquals(2, replay.size());
        assertTrue(replay.get(0).contains(":PRICE:" + (T + 2)));
        assertTrue(replay.get(1).contains(":PRICE:" + (T + 3)));
    }

    @Test
    @DisplayName("shadow mode drains but sends nothing, live or in the snapshot")
    void shadowMode() {
        SpikeAlertProperties off = props(false, 2);
        SpikeAlertStore shadowStore = new SpikeAlertStore(off);
        SpikeChannel shadow = new SpikeChannel(shadowStore, off);
        shadowStore.add(price(1));
        shadow.drain();

        List<String> out = new ArrayList<>();
        shadow.collectUpdates(session(), out);
        shadow.collectSnapshot(session(), out);
        assertTrue(out.isEmpty());
        assertEquals(1, shadowStore.recent(null, null, null, 10).size());
    }

    private static SpikeAlert price(int i) {
        return new SpikeAlert(PEPE, SpikeKind.PRICE, 5.5, 1, 1.055, 5, T + i, T + i);
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

    private static UserWebSocketSession session() {
        return new UserWebSocketSession(null, UUID.randomUUID());
    }
}
