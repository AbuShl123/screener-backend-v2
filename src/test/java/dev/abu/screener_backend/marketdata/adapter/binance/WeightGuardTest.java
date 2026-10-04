package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.marketdata.adapter.binance.WeightGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightGuardTest {

    /** 12:00:10 server time — well inside its minute, so window arithmetic is unambiguous. */
    private static final long T0 = Instant.parse("2026-10-02T12:00:10Z").toEpochMilli();
    private static final long LIMIT = 5800;

    private static HttpHeaders headers(String weight, long serverTimeMs) {
        HttpHeaders h = new HttpHeaders();
        if (weight != null) h.set(WeightGuard.WEIGHT_HEADER, weight);
        h.setDate(ZonedDateTime.ofInstant(Instant.ofEpochMilli(serverTimeMs), ZoneOffset.UTC));
        return h;
    }

    @Test
    @DisplayName("before any observation the whole limit is available")
    void nothingObserved() {
        assertEquals(LIMIT, new WeightGuard().remaining(LIMIT, T0));
    }

    @Test
    @DisplayName("parses the weight header and anchors it to the server's Date header")
    void parsesHeaders() {
        WeightGuard guard = new WeightGuard();

        guard.observe(headers("1200", T0), T0);

        assertEquals(LIMIT - 1200, guard.remaining(LIMIT, T0 + 1_000));
    }

    @Test
    @DisplayName("headers without a weight, or with garbage, change nothing")
    void ignoresMissingOrBadWeight() {
        WeightGuard guard = new WeightGuard();

        guard.observe(headers(null, T0), T0);
        guard.observe(headers("lots", T0), T0);
        guard.observe(null, T0);

        assertEquals(LIMIT, guard.remaining(LIMIT, T0));
    }

    @Test
    @DisplayName("the window rolls at the next server-minute boundary, not 60s after the observation")
    void windowRollsAtMinuteBoundary() {
        WeightGuard guard = new WeightGuard();
        guard.observe(T0, T0, 5000);   // 12:00:10

        long boundary = Instant.parse("2026-10-02T12:01:00Z").toEpochMilli();
        assertEquals(LIMIT - 5000, guard.remaining(LIMIT, boundary - 1));
        assertEquals(LIMIT, guard.remaining(LIMIT, boundary));
    }

    @Test
    @DisplayName("a local clock running ahead does not roll the window early (the live 429)")
    void localClockAheadDoesNotRollEarly() {
        WeightGuard guard = new WeightGuard();
        long skew = 2_300;   // measured live: local 2.3s ahead of Binance
        guard.observe(T0, T0 + skew, 5000);   // server 12:00:10, received at local 12:00:12.3

        // Local 12:01:00.5 is server 12:00:58.2 — still the old window. Comparing local now with the
        // server boundary would call it rolled and send a full batch into an exhausted minute.
        long localMinuteFlip = Instant.parse("2026-10-02T12:01:00.500Z").toEpochMilli();
        assertEquals(LIMIT - 5000, guard.remaining(LIMIT, localMinuteFlip));

        // 50s of elapsed time after receipt covers what was left of the server minute.
        assertEquals(LIMIT - 5000, guard.remaining(LIMIT, T0 + skew + 49_999));
        assertEquals(LIMIT, guard.remaining(LIMIT, T0 + skew + 50_000));
    }

    @Test
    @DisplayName("a local clock running behind does not hold the window shut")
    void localClockBehindStillRolls() {
        WeightGuard guard = new WeightGuard();
        guard.observe(T0, T0 - 5_000, 5000);   // local 5s behind

        assertEquals(LIMIT, guard.remaining(LIMIT, T0 - 5_000 + 50_000), "rolls on elapsed time, not the local wall clock");
    }

    @Test
    @DisplayName("a lighter reading from the same minute arrived out of order and is discarded")
    void sameMinuteLighterDiscarded() {
        WeightGuard guard = new WeightGuard();
        guard.observe(T0 + 2_000, T0 + 2_000, 900);

        guard.observe(T0 + 2_000, T0 + 2_000, 400);   // same second, lighter — a response that lost the race
        guard.observe(T0, T0, 300);           // earlier and lighter

        assertEquals(LIMIT - 900, guard.remaining(LIMIT, T0 + 3_000));
    }

    @Test
    @DisplayName("a reading from an earlier minute is discarded, even when heavier")
    void earlierMinuteDiscarded() {
        WeightGuard guard = new WeightGuard();
        guard.observe(T0 + 60_000, T0 + 60_000, 100);   // 12:01:10

        guard.observe(T0, T0, 5000);           // 12:00:10 — stale

        assertEquals(LIMIT - 100, guard.remaining(LIMIT, T0 + 61_000));
    }

    @Test
    @DisplayName("a heavier reading, or one from a new minute, replaces the last")
    void newerReadingsReplace() {
        WeightGuard guard = new WeightGuard();
        guard.observe(T0, T0, 500);
        guard.observe(T0 + 1_000, T0 + 1_000, 700);
        assertEquals(LIMIT - 700, guard.remaining(LIMIT, T0 + 1_000));

        guard.observe(T0 + 60_000, T0 + 60_000, 50);
        assertEquals(LIMIT - 50, guard.remaining(LIMIT, T0 + 60_000));
    }

    @Test
    @DisplayName("ban holds until its deadline and is never shortened")
    void banNeverShortens() {
        WeightGuard guard = new WeightGuard();
        assertFalse(guard.isBanned(T0));

        guard.ban(T0 + 8_000);
        guard.ban(T0 + 2_000);

        assertTrue(guard.isBanned(T0 + 7_999));
        assertFalse(guard.isBanned(T0 + 8_000));
    }
}
