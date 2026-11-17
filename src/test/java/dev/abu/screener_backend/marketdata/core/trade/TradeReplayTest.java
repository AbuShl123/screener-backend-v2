package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceAggTradeDecoder;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Replays real captured Binance aggTrade frames through {@link BinanceAggTradeDecoder} and
 * {@link TradeDetector} and checks that exactly the alerts the slice 0 analysis script found are
 * raised: the Java code against an independent implementation, on real data.
 *
 * <p>The fixture and its expected alerts are produced by the analysis
 * ({@code .claude/plans/trade-detection-plan.md} §3.3, which also specifies both file formats). The
 * test skips itself while the fixture is absent. The thresholds come from the expected-alerts file,
 * never from the shipped YAML, so retuning production thresholds does not break the replay.
 */
class TradeReplayTest {

    private static final String FIXTURE = "trade/binance-aggtrade-replay.jsonl";
    private static final String EXPECTED = "trade/binance-aggtrade-replay-expected.json";
    private static final double REL_TOLERANCE = 1e-9;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static List<String> lines;
    private static JsonNode expected;

    @BeforeAll
    static void load() throws IOException {
        ClassLoader cl = TradeReplayTest.class.getClassLoader();
        if (cl.getResource(FIXTURE) == null || cl.getResource(EXPECTED) == null) return;
        try (InputStream in = cl.getResourceAsStream(FIXTURE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            lines = reader.lines().filter(l -> !l.isBlank()).toList();
        }
        try (InputStream in = cl.getResourceAsStream(EXPECTED)) {
            expected = JSON.readTree(in);
        }
    }

    @Test
    @DisplayName("coalescing sweeps, the fixture raises exactly the analysis's coalesced alerts")
    void coalescedAlerts() {
        assumeFixture();
        List<Alert> actual = replay();

        assertEquals(0, failures, "every fixture frame decodes");
        assertTrue(expected.required("coalescedOpenAtEnd").isEmpty(),
                "the fixture is meant to close every sweep it opens");
        List<Alert> want = alerts(expected.required("coalesced"));
        assertMatches(want, actual);
        assertEquals(want.stream().map(Alert::key).toList(), actual.stream().map(Alert::key).toList(),
                "a sweep is judged when the instrument's next trade arrives, so emission order matches too");
    }

    /**
     * Under the same thresholds, an alert is coalescing-only exactly when no single-trade alert falls
     * inside its id range: the expected {@code singleTrade} list is the independent check of the flag.
     */
    @Test
    @DisplayName("an alert is coalescing-only exactly when none of its fills alerts on its own")
    void coalescedOnlyFlag() {
        assumeFixture();
        List<Alert> single = alerts(expected.required("singleTrade"));
        replay();

        int coalescedOnly = 0;
        for (TradeAlert a : alerted) {
            String venue = a.instrument().venue().name();
            String symbol = a.instrument().nativeSymbol();
            boolean fillAlone = single.stream().anyMatch(s -> s.venue().name().equals(venue)
                    && s.symbol().equals(symbol)
                    && s.firstTradeId() >= a.firstTradeId() && s.lastTradeId() <= a.lastTradeId());
            assertEquals(!fillAlone, a.coalescedOnly(), a.id());
            if (a.coalescedOnly()) coalescedOnly++;
        }
        assertTrue(coalescedOnly > 0, "the fixture is meant to hold alerts that exist only because of coalescing");
    }

    @Test
    @DisplayName("alert ids are unique and follow VENUE:SYMBOL:firstTradeId")
    void stableIds() {
        assumeFixture();
        replay();

        assertEquals(alerted.size(), alerted.stream().map(TradeAlert::id).distinct().count());
        for (TradeAlert a : alerted) {
            assertEquals(a.instrument().venue().name() + ":" + a.instrument().nativeSymbol() + ":"
                    + a.firstTradeId(), a.id());
        }
    }

    /** Skips, visibly, until the analysis delivers the fixture. */
    private static void assumeFixture() {
        assumeTrue(lines != null && expected != null,
                "replay fixture not present yet (" + FIXTURE + ", produced by the slice 0 analysis)");
    }

    // ---- replay ----

    private int failures;
    /** The alerts as raised, oldest first; set by {@link #replay()}. */
    private List<TradeAlert> alerted;

    private List<Alert> replay() {
        TradeAlertProperties props = thresholds(expected.required("thresholds"), lines.size() + 1);
        TradeAlertStore store = new TradeAlertStore(props);
        TradeDetector detector = new TradeDetector(new PipelineMetrics(), new TradeThresholds(props), store);
        BinanceAggTradeDecoder decoder = new BinanceAggTradeDecoder();
        Map<String, InstrumentSlot> slots = new HashMap<>();

        for (String line : lines) {
            JsonNode rec = JSON.readTree(line);
            Venue venue = Venue.valueOf(rec.required("v").asString());
            JsonNode frame = rec.required("d");
            String symbol = frame.required("s").asString();
            InstrumentSlot slot = slots.computeIfAbsent(venue + "/" + symbol,
                    k -> new InstrumentSlot(Instrument.of(slots.size(), venue, symbol,
                            symbol.substring(0, symbol.length() - 4), "USDT"), null, null, null, decoder));
            if (!decoder.decode(slot, frame.toString(), detector)) failures++;
        }

        alerted = store.recent(null, null, Integer.MAX_VALUE).reversed();
        List<Alert> out = new ArrayList<>();
        for (TradeAlert a : alerted) {
            out.add(new Alert(a.instrument().venue(), a.instrument().nativeSymbol(),
                    a.aggressorBuy() ? "BUY" : "SELL", a.firstTradeId(), a.lastTradeId(), a.fills(),
                    a.tradeTimeMs(), a.qty(), a.notional()));
        }
        return out;
    }

    private static TradeAlertProperties thresholds(JsonNode node, int capacity) {
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
        return new TradeAlertProperties(node.required("defaultMinNotional").asDouble(), overrides, capacity);
    }

    // ---- comparison ----

    /** One alert as the expected-alerts file spells it. */
    record Alert(Venue venue, String symbol, String side, long firstTradeId, long lastTradeId, int fills,
                 long tradeTimeMs, double qty, double notional) {

        /** Identity of the alert: which trades it covers. */
        String key() {
            return venue + "/" + symbol + "#" + firstTradeId + "-" + lastTradeId;
        }
    }

    private static List<Alert> alerts(JsonNode array) {
        List<Alert> out = new ArrayList<>();
        for (JsonNode a : array) {
            out.add(new Alert(Venue.valueOf(a.required("venue").asString()), a.required("symbol").asString(),
                    a.required("side").asString(), a.required("firstTradeId").asLong(),
                    a.required("lastTradeId").asLong(), a.required("fills").asInt(),
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
}
