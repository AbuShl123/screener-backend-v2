package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceAggTradeDecoder;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcFuturesDealDecoder;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotDealsDecoder;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeDecoder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Replays real captured trade frames through a venue's decoder and {@link TradeDetector} and checks
 * that exactly the alerts an independent analysis script found are raised: the Java code against an
 * independent implementation, on real data.
 *
 * <p>Each fixture and its expected alerts come from the analysis scripts in
 * {@code C:\git\trade-capture\analysis\} ({@code make_fixture.py} for Binance,
 * {@code make_mexc_fixture.py} for MEXC spot, {@code make_mexc_futures_fixture.py} for MEXC futures).
 * A test skips itself while its fixture is absent. The thresholds and the idle close come from the
 * expected-alerts file, never from the shipped YAML, so retuning production values does not break the
 * replay.
 */
class TradeReplayTest {

    private static final double REL_TOLERANCE = 1e-9;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Binance aggTrade: sweeps close on the instrument's next trade. Format:
     * {@code .claude/plans/trade-detection-plan.md} §3.3.
     */
    @Nested
    class Binance {

        private static Fixture fixture;

        @BeforeAll
        static void load() throws IOException {
            fixture = Fixture.load("trade/binance-aggtrade-replay.jsonl", "trade/binance-aggtrade-replay-expected.json");
        }

        @Test
        @DisplayName("coalescing sweeps, the fixture raises exactly the analysis's coalesced alerts")
        void coalescedAlerts() {
            Replay replay = replay();

            assertEquals(0, replay.failures(), "every fixture frame decodes");
            assertTrue(fixture.expected().required("coalescedOpenAtEnd").isEmpty(),
                    "the fixture is meant to close every sweep it opens");
            List<Alert> want = alerts(fixture.expected().required("coalesced"));
            assertMatches(want, replay.view());
            assertEquals(want.stream().map(Alert::key).toList(), replay.view().stream().map(Alert::key).toList(),
                    "a sweep is judged when the instrument's next trade arrives, so emission order matches too");
        }

        /**
         * Under the same thresholds, an alert is coalescing-only exactly when no single-trade alert
         * falls inside its id range: the expected {@code singleTrade} list is the independent check
         * of the flag. On Binance every fill of a sweep is its own price level.
         */
        @Test
        @DisplayName("an alert is coalescing-only exactly when none of its fills alerts on its own")
        void coalescedOnlyFlag() {
            List<Alert> single = alerts(fixture.expected().required("singleTrade"));
            Replay replay = replay();

            int coalescedOnly = 0;
            for (TradeAlert a : replay.alerts()) {
                String venue = a.instrument().venue().name();
                String symbol = a.instrument().nativeSymbol();
                boolean fillAlone = single.stream().anyMatch(s -> s.venue().name().equals(venue)
                        && s.symbol().equals(symbol)
                        && s.firstTradeId() >= a.firstTradeId() && s.lastTradeId() <= a.lastTradeId());
                assertEquals(!fillAlone, a.coalescedOnly(), a.id());
                assertEquals(a.fills(), a.levels(), a.id() + ": one level per fill on Binance");
                if (a.coalescedOnly()) coalescedOnly++;
            }
            assertTrue(coalescedOnly > 0, "the fixture is meant to hold alerts that exist only because of coalescing");
        }

        @Test
        @DisplayName("alert ids are unique and follow VENUE:SYMBOL:firstTradeId")
        void stableIds() {
            assertStableIds(replay().alerts());
        }

        private Replay replay() {
            assumeTrue(fixture != null, "Binance replay fixture not present");
            BinanceAggTradeDecoder decoder = new BinanceAggTradeDecoder();
            return Replay.run(fixture, (rec, slots) -> {
                Venue venue = Venue.valueOf(rec.required("v").asString());
                JsonNode frame = rec.required("d");
                String symbol = frame.required("s").asString();
                InstrumentSlot slot = slots.slot(venue, symbol, decoder);
                return decoder.decode(slot, frame.toString(), slots.detector());
            });
        }
    }

    /**
     * MEXC spot deals: {@code X0_X0} deals filtered, sweeps grouped by taker-order id and closed at
     * the end of each frame ({@code .claude/plans/trade-mexc-plan.md} §5).
     */
    @Nested
    class MexcSpot {

        private static Fixture fixture;

        @BeforeAll
        static void load() throws IOException {
            fixture = Fixture.load("trade/mexc-spot-deals-replay.jsonl", "trade/mexc-spot-deals-replay-expected.json");
        }

        @Test
        @DisplayName("the fixture raises exactly the analysis's alerts, in order, with levels and per-level coalescedOnly")
        void expectedAlerts() {
            Replay replay = replay();

            assertEquals(0, replay.failures(), "every fixture frame decodes");
            List<Alert> want = alerts(fixture.expected().required("coalesced"));
            assertMatches(want, replay.view());
            assertEquals(want.stream().map(Alert::key).toList(), replay.view().stream().map(Alert::key).toList(),
                    "a sweep is judged at the end of its frame, so emission order matches too");
            assertTrue(want.stream().anyMatch(a -> a.levels() < a.fills()), "the fixture holds equal-price fills");
            assertTrue(want.stream().anyMatch(a -> a.levels() > 1), "the fixture holds multi-level orders");
        }

        @Test
        @DisplayName("X0_X0 deals are filtered: counted, never alerting, though many would")
        void filtered() {
            Replay replay = replay();

            assertEquals(fixture.expected().required("filteredDeals").asLong(),
                    replay.metrics().tradesFiltered(Venue.MEXC_SPOT));
            assertTrue(fixture.expected().required("x0DealsThatWouldAlert").asLong() > 0,
                    "the fixture holds X0_X0 deals at or above their threshold");
            for (TradeAlert a : replay.alerts()) assertTrue(a.firstTradeId() >= 0, a.id() + " is a real fill");
        }

        @Test
        @DisplayName("an order whose fills span two frames alerts on the first frame's fills alone")
        void orderSpanningFrames() {
            JsonNode cross = fixture.expected().required("crossFrameOrders");
            assertFalse(cross.isEmpty(), "the fixture holds an order spanning two frames");
            Replay replay = replay();

            for (JsonNode order : cross) {
                long taker = order.required("takerOrderId").asLong();
                JsonNode alert = null;
                for (JsonNode a : fixture.expected().required("coalesced")) {
                    if (a.required("takerOrderId").asLong() == taker) alert = a;
                }
                if (alert == null) continue;
                long first = alert.required("firstTradeId").asLong();
                TradeAlert got = replay.alerts().stream().filter(a -> a.firstTradeId() == first).findFirst().orElseThrow();
                assertEquals(alert.required("fills").asInt(), got.fills());
            }
        }

        @Test
        @DisplayName("alert ids are unique and follow VENUE:SYMBOL:firstTradeId")
        void stableIds() {
            assertStableIds(replay().alerts());
        }

        private Replay replay() {
            assumeTrue(fixture != null, "MEXC spot replay fixture not present");
            MexcSpotDealsDecoder decoder = new MexcSpotDealsDecoder();
            return Replay.run(fixture, (rec, slots) -> {
                byte[] frame = Base64.getDecoder().decode(rec.required("d").asString());
                InstrumentSlot slot = slots.slot(Venue.MEXC_SPOT, spotSymbol(frame), decoder);
                boolean decoded = decoder.decode(slot, ByteBuffer.wrap(frame), slots.detector());
                slots.detector().onFrameEnd(slot);
                return decoded;
            });
        }

        /** The wrapper's {@code symbol} (field 3), read with a minimal Protobuf walk. */
        private static String spotSymbol(byte[] frame) {
            int pos = 0;
            while (pos < frame.length) {
                long tag = 0;
                for (int shift = 0; ; shift += 7) {
                    byte b = frame[pos++];
                    tag |= (long) (b & 0x7F) << shift;
                    if (b >= 0) break;
                }
                int wireType = (int) (tag & 7);
                if (wireType == 0) {
                    while (frame[pos++] < 0) { }
                    continue;
                }
                if (wireType != 2) throw new IllegalStateException("unexpected wire type " + wireType);
                int length = 0;
                for (int shift = 0; ; shift += 7) {
                    byte b = frame[pos++];
                    length |= (b & 0x7F) << shift;
                    if (b >= 0) break;
                }
                if (tag >>> 3 == 3) return new String(frame, pos, length, StandardCharsets.US_ASCII);
                pos += length;
            }
            throw new IllegalStateException("frame without symbol");
        }
    }

    /**
     * MEXC futures deals: self-trades ({@code M=1}) filtered, rule A (same side and {@code t}, next
     * {@code i}, price equal or toward the aggressor), and the idle close on the records' receive time
     * ({@code .claude/plans/trade-mexc-plan.md} §6). The detector's clock is each record's {@code r},
     * and before each record {@code closeIfIdle} runs on every slot, as a shard tick would.
     */
    @Nested
    class MexcFutures {

        private static Fixture fixture;

        @BeforeAll
        static void load() throws IOException {
            fixture = Fixture.load("trade/mexc-futures-deal-replay.jsonl", "trade/mexc-futures-deal-replay-expected.json");
        }

        @Test
        @DisplayName("the fixture raises exactly the analysis's alerts, in order, each at the same local time")
        void expectedAlerts() {
            Replay replay = replay();

            assertEquals(0, replay.failures(), "every fixture frame decodes");
            JsonNode coalesced = fixture.expected().required("coalesced");
            List<Alert> want = alerts(coalesced);
            assertMatches(want, replay.view());
            assertEquals(want.stream().map(Alert::key).toList(), replay.view().stream().map(Alert::key).toList(),
                    "emission order matches: idle closes run in the slots' order before each record");
            for (int i = 0; i < coalesced.size(); i++) {
                assertEquals(coalesced.get(i).required("detectedAtMs").asLong(), replay.alerts().get(i).detectedAtMs(),
                        replay.alerts().get(i).id() + " closed by " + coalesced.get(i).required("closedBy").asString());
            }
            assertTrue(want.stream().anyMatch(a -> a.levels() < a.fills()), "the fixture holds equal-price fills");
            assertTrue(fixture.expected().required("multiDealFrames").asInt() > 0, "the fixture holds multi-deal frames");
            assertTrue(fixture.expected().required("oneMsSteps").asInt() > 0, "the fixture holds 1 ms steps that close a sweep");
        }

        @Test
        @DisplayName("the idle close emits alerts a next-deal close would not reach within the window, and counts its splits")
        void idleClose() {
            Replay replay = replay();

            JsonNode idleOnly = fixture.expected().required("idleOnly");
            assertFalse(idleOnly.isEmpty(), "the fixture holds alerts only the idle close emits");
            for (JsonNode id : idleOnly) {
                assertTrue(replay.alerts().stream().anyMatch(a -> a.firstTradeId() == id.asLong()), "alert " + id);
            }
            long idleClosedAlerts = 0;
            for (JsonNode a : fixture.expected().required("coalesced")) {
                if (a.required("closedBy").asString().equals("idle")) idleClosedAlerts++;
            }
            assertTrue(replay.metrics().tradeIdleCloses(Venue.MEXC_FUTURES) >= idleClosedAlerts,
                    "every idle-closed alert was counted as an idle close");
            assertEquals(fixture.expected().required("idleSplits").asLong(),
                    replay.metrics().tradeIdleSplits(Venue.MEXC_FUTURES));
            for (JsonNode open : fixture.expected().required("coalescedOpenAtEnd")) {
                long first = open.required("firstTradeId").asLong();
                assertTrue(replay.alerts().stream().noneMatch(a -> a.firstTradeId() == first), "still open: " + first);
            }
        }

        @Test
        @DisplayName("self-trades are filtered: counted, never alerting, though one would")
        void filtered() {
            Replay replay = replay();

            assertEquals(fixture.expected().required("filteredDeals").asLong(),
                    replay.metrics().tradesFiltered(Venue.MEXC_FUTURES));
            assertTrue(fixture.expected().required("m1DealsThatWouldAlert").asLong() > 0,
                    "the fixture holds a self-trade at or above its threshold");
        }

        @Test
        @DisplayName("alert ids are unique and follow VENUE:SYMBOL:firstTradeId")
        void stableIds() {
            assertStableIds(replay().alerts());
        }

        private Replay replay() {
            assumeTrue(fixture != null, "MEXC futures replay fixture not present");
            MexcFuturesDealDecoder decoder = new MexcFuturesDealDecoder();
            JsonNode sizes = fixture.expected().required("contractSize");
            return Replay.run(fixture, (rec, slots) -> {
                slots.now = rec.required("r").asLong();
                for (InstrumentSlot s : slots.all()) slots.detector().closeIfIdle(s);
                JsonNode frame = rec.required("d");
                String symbol = frame.required("symbol").asString();
                InstrumentSlot slot = slots.slot(Venue.MEXC_FUTURES, symbol, decoder,
                        sizes.required(symbol).asDouble());
                return decoder.decode(slot, frame.toString(), slots.detector());
            });
        }
    }

    // ---- replay ----

    private record Fixture(List<String> lines, JsonNode expected) {

        /** {@code null} when either file is missing. */
        static Fixture load(String lines, String expected) throws IOException {
            ClassLoader cl = TradeReplayTest.class.getClassLoader();
            if (cl.getResource(lines) == null || cl.getResource(expected) == null) return null;
            List<String> read;
            try (InputStream in = cl.getResourceAsStream(lines);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                read = reader.lines().filter(l -> !l.isBlank()).toList();
            }
            try (InputStream in = cl.getResourceAsStream(expected)) {
                return new Fixture(read, JSON.readTree(in));
            }
        }
    }

    /**
     * Slots by {@code (venue, symbol)}, created on first sight and kept in that order, the detector
     * they report into, and the detector's clock.
     */
    private static final class Slots {
        private final Map<String, InstrumentSlot> byKey = new LinkedHashMap<>();
        private TradeDetector detector;
        /** The detector's local clock, ms; the futures replay sets it to each record's {@code r}. */
        long now;

        TradeDetector detector() {
            return detector;
        }

        /** In creation order, like the shard tick's walk over ascending ids. */
        Iterable<InstrumentSlot> all() {
            return byKey.values();
        }

        InstrumentSlot slot(Venue venue, String symbol, TradeDecoder decoder) {
            return slot(venue, symbol, decoder, 1);
        }

        InstrumentSlot slot(Venue venue, String symbol, TradeDecoder decoder, double quantityMultiplier) {
            String pair = symbol.replace("_", "");
            return byKey.computeIfAbsent(venue + "/" + symbol, k -> new InstrumentSlot(
                    Instrument.of(byKey.size(), venue, symbol, pair.substring(0, pair.length() - 4), "USDT",
                            quantityMultiplier),
                    null, null, null, decoder));
        }
    }

    @FunctionalInterface
    private interface LineReplayer {
        /** Replays one fixture line; {@code false} if its frame failed to decode. */
        boolean replay(JsonNode record, Slots slots);
    }

    /**
     * @param alerts as raised, oldest first
     */
    private record Replay(int failures, List<TradeAlert> alerts, PipelineMetrics metrics) {

        static Replay run(Fixture fixture, LineReplayer replayer) {
            TradeAlertProperties props = properties(fixture.expected(), fixture.lines().size() + 1);
            TradeAlertStore store = new TradeAlertStore(props);
            PipelineMetrics metrics = new PipelineMetrics();
            Slots slots = new Slots();
            slots.detector = new TradeDetector(metrics, props, new TradeThresholds(props), store, () -> slots.now);
            int failures = 0;
            for (String line : fixture.lines()) {
                if (!replayer.replay(JSON.readTree(line), slots)) failures++;
            }
            return new Replay(failures, store.recent(null, null, Integer.MAX_VALUE).reversed(), metrics);
        }

        List<Alert> view() {
            List<Alert> out = new ArrayList<>();
            for (TradeAlert a : alerts) {
                out.add(new Alert(a.instrument().venue(), a.instrument().nativeSymbol(),
                        a.aggressorBuy() ? "BUY" : "SELL", a.firstTradeId(), a.lastTradeId(), a.fills(),
                        a.levels(), a.coalescedOnly(), a.tradeTimeMs(), a.qty(), a.notional()));
            }
            return out;
        }
    }

    /** Thresholds from {@code thresholds}, and the MEXC futures idle close from {@code sweepIdleCloseMs} if present. */
    private static TradeAlertProperties properties(JsonNode expected, int capacity) {
        JsonNode node = expected.required("thresholds");
        Map<Venue, Long> idle = expected.has("sweepIdleCloseMs")
                ? Map.of(Venue.MEXC_FUTURES, expected.get("sweepIdleCloseMs").asLong())
                : null;
        Map<Venue, Map<String, Double>> overrides = new HashMap<>();
        JsonNode perVenue = node.path("minNotional");
        for (String venue : perVenue.propertyNames()) {
            Map<String, Double> symbols = new HashMap<>();
            JsonNode perSymbol = perVenue.get(venue);
            for (String symbol : perSymbol.propertyNames()) {
                symbols.put(symbol, perSymbol.get(symbol).asDouble());
            }
            overrides.put(Venue.valueOf(venue), symbols);
        }
        return new TradeAlertProperties(node.required("defaultMinNotional").asDouble(), overrides, capacity, capacity,
                idle, 250);
    }

    // ---- comparison ----

    /**
     * One alert as the expected-alerts file spells it. {@code levels} and {@code coalescedOnly} are
     * in the MEXC file only: {@code levels} defaults to {@code fills} (one level per fill on Binance),
     * and a missing {@code coalescedOnly} is not compared.
     */
    record Alert(Venue venue, String symbol, String side, long firstTradeId, long lastTradeId, int fills,
                 int levels, Boolean coalescedOnly, long tradeTimeMs, double qty, double notional) {

        /** Identity of the alert: which trades it covers. */
        String key() {
            return venue + "/" + symbol + "#" + firstTradeId + "-" + lastTradeId;
        }
    }

    private static List<Alert> alerts(JsonNode array) {
        List<Alert> out = new ArrayList<>();
        for (JsonNode a : array) {
            int fills = a.required("fills").asInt();
            out.add(new Alert(Venue.valueOf(a.required("venue").asString()), a.required("symbol").asString(),
                    a.required("side").asString(), a.required("firstTradeId").asLong(),
                    a.required("lastTradeId").asLong(), fills, a.path("levels").asInt(fills),
                    a.has("coalescedOnly") ? a.get("coalescedOnly").asBoolean() : null,
                    a.required("tradeTimeMs").asLong(), a.required("qty").asDouble(),
                    a.required("notional").asDouble()));
        }
        return out;
    }

    private static void assertMatches(List<Alert> expectedAlerts, List<Alert> actualAlerts) {
        assertTrue(!expectedAlerts.isEmpty(), "the fixture is meant to contain alerts");
        Map<String, Alert> want = byKey(expectedAlerts);
        Map<String, Alert> got = byKey(actualAlerts);

        List<String> missing = want.keySet().stream().filter(k -> !got.containsKey(k)).sorted().toList();
        List<String> extra = got.keySet().stream().filter(k -> !want.containsKey(k)).sorted().toList();
        if (!missing.isEmpty() || !extra.isEmpty()) {
            fail("alerts differ from the analysis: missing " + missing + ", extra " + extra);
        }
        for (Map.Entry<String, Alert> e : want.entrySet()) {
            Alert w = e.getValue();
            Alert g = got.get(e.getKey());
            String ctx = e.getKey();
            assertEquals(w.side(), g.side(), ctx + " side");
            assertEquals(w.fills(), g.fills(), ctx + " fills");
            assertEquals(w.levels(), g.levels(), ctx + " levels");
            if (w.coalescedOnly() != null) assertEquals(w.coalescedOnly(), g.coalescedOnly(), ctx + " coalescedOnly");
            assertEquals(w.tradeTimeMs(), g.tradeTimeMs(), ctx + " tradeTimeMs");
            assertEquals(w.qty(), g.qty(), Math.abs(w.qty()) * REL_TOLERANCE, ctx + " qty");
            assertEquals(w.notional(), g.notional(), Math.abs(w.notional()) * REL_TOLERANCE, ctx + " notional");
        }
        assertEquals(expectedAlerts.size(), actualAlerts.size(), "no duplicate alerts");
    }

    private static Map<String, Alert> byKey(List<Alert> alerts) {
        return alerts.stream()
                .sorted(Comparator.comparing(Alert::key))
                .collect(Collectors.toMap(Alert::key, Function.identity(), (a, b) -> a));
    }

    private static void assertStableIds(List<TradeAlert> alerts) {
        assertEquals(alerts.size(), alerts.stream().map(TradeAlert::id).distinct().count());
        for (TradeAlert a : alerts) {
            assertEquals(a.instrument().venue().name() + ":" + a.instrument().nativeSymbol() + ":"
                    + a.firstTradeId(), a.id());
        }
    }
}
