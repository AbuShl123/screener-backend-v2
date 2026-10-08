package dev.abu.screener_backend.analysis.filter;

import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Books around mid 100.0 (dust at 99.99 / 100.01), so a level's distance is {@code |price - 100| / 100}.
 * Candidates sit at 0.5% from mid.
 */
class MmFingerprintFilterTest {

    private static final long T0 = 1_700_000_000_000L;

    private final MmFingerprintFilter filter = new MmFingerprintFilter(0.02, 0.001, 0.01, 0.25);

    private static OrderBook book() {
        OrderBook ob = new OrderBook(0.1, 2.0);
        ob.applyLevel(true, 99.99, 0.01, T0);
        ob.applyLevel(false, 100.01, 0.01, T0);
        return ob;
    }

    private boolean accepts(OrderBook ob, boolean isBid, double price) {
        ob.computeDistance();
        PriceLevelEntry entry = (isBid ? ob.getBids() : ob.getAsks()).get(price);
        return filter.accept(Map.entry(price, entry), isBid, ob, T0);
    }

    @Test
    @DisplayName("a one-sided wall with no twin and no mirror is accepted")
    void oneSidedWall() {
        OrderBook ob = book();
        ob.applyLevel(true, 99.5, 1000.0, T0);
        // Other levels near it, none an exact copy or a mirror.
        ob.applyLevel(true, 99.4, 700.0, T0);
        ob.applyLevel(true, 99.0, 1200.0, T0);
        ob.applyLevel(false, 100.5, 500.0, T0);
        ob.applyLevel(false, 100.6, 2000.0, T0);

        assertTrue(accepts(ob, true, 99.5));
    }

    @Test
    @DisplayName("a same-side twin within 2% of mid rejects the level, one beyond 2% does not")
    void twinDistance() {
        OrderBook inside = book();
        inside.applyLevel(true, 99.5, 1000.0, T0);
        inside.applyLevel(true, 98.5, 1000.0, T0); // 1.5%
        assertFalse(accepts(inside, true, 99.5));

        OrderBook outside = book();
        outside.applyLevel(true, 99.5, 1000.0, T0);
        outside.applyLevel(true, 97.5, 1000.0, T0); // 2.5%
        assertTrue(accepts(outside, true, 99.5));
    }

    @Test
    @DisplayName("a twin's quantity must match within 0.1%")
    void twinQuantityTolerance() {
        OrderBook inside = book();
        inside.applyLevel(true, 99.5, 1000.0, T0);
        inside.applyLevel(true, 99.0, 1000.9, T0);
        assertFalse(accepts(inside, true, 99.5));

        OrderBook outside = book();
        outside.applyLevel(true, 99.5, 1000.0, T0);
        outside.applyLevel(true, 99.0, 1001.1, T0);
        assertTrue(accepts(outside, true, 99.5));
    }

    @Test
    @DisplayName("an equal quantity on the other side is not a twin")
    void twinIsSameSideOnly() {
        OrderBook ob = book();
        ob.applyLevel(true, 99.5, 1000.0, T0);
        ob.applyLevel(false, 101.5, 1000.0, T0); // same quantity, 1.5%, outside the mirror band

        assertTrue(accepts(ob, true, 99.5));
    }

    @Test
    @DisplayName("an other-side level with notional within 1% at distance within 25% is a mirror")
    void mirrorInsideBothTolerances() {
        OrderBook ob = book();
        ob.applyLevel(true, 99.5, 1000.0, T0);                   // $99,500 at 0.5%
        ob.applyLevel(false, 100.6, 99_500.0 * 1.005 / 100.6, T0); // +0.5% notional at 0.6% (+20%)

        assertFalse(accepts(ob, true, 99.5));
    }

    @Test
    @DisplayName("a mirror candidate outside the notional tolerance does not reject")
    void mirrorOutsideNotionalTolerance() {
        OrderBook ob = book();
        ob.applyLevel(true, 99.5, 1000.0, T0);
        ob.applyLevel(false, 100.5, 99_500.0 * 1.02 / 100.5, T0); // +2% notional, same distance

        assertTrue(accepts(ob, true, 99.5));
    }

    @Test
    @DisplayName("a mirror candidate outside the distance tolerance does not reject, on either side of the band")
    void mirrorOutsideDistanceTolerance() {
        OrderBook further = book();
        further.applyLevel(true, 99.5, 1000.0, T0);
        further.applyLevel(false, 100.7, 99_500.0 / 100.7, T0); // 0.7%: +40%
        assertTrue(accepts(further, true, 99.5));

        OrderBook closer = book();
        closer.applyLevel(true, 99.5, 1000.0, T0);
        closer.applyLevel(false, 100.3, 99_500.0 / 100.3, T0); // 0.3%: −40%
        assertTrue(accepts(closer, true, 99.5));
    }

    @Test
    @DisplayName("an ask is checked against asks for twins and bids for mirrors")
    void askSide() {
        OrderBook twin = book();
        twin.applyLevel(false, 100.5, 1000.0, T0);
        twin.applyLevel(false, 101.0, 1000.0, T0);
        assertFalse(accepts(twin, false, 100.5));

        OrderBook mirror = book();
        mirror.applyLevel(false, 100.5, 1000.0, T0);             // $100,500 at 0.5%
        mirror.applyLevel(true, 99.5, 100_500.0 / 99.5, T0);     // same notional at 0.5%
        assertFalse(accepts(mirror, false, 100.5));

        OrderBook alone = book();
        alone.applyLevel(false, 100.5, 1000.0, T0);
        assertTrue(accepts(alone, false, 100.5));
    }
}
