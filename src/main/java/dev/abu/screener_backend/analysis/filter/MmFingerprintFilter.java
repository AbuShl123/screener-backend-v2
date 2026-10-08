package dev.abu.screener_backend.analysis.filter;

import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;

import java.util.Map;
import java.util.TreeMap;

/**
 * Rejects a level that carries an exact market-maker fingerprint in the current book:
 * <ul>
 *   <li><b>Exact twin</b>: another level on the same side, within {@code twinMaxDistance} of mid,
 *       has the same quantity within {@code twinQuantityTolerance}.</li>
 *   <li><b>Tight mirror</b>: a level on the other side has the same notional within
 *       {@code mirrorNotionalTolerance}, at a distance within {@code mirrorDistanceTolerance} of this
 *       level's.</li>
 * </ul>
 * Tolerances are fractions of the candidate's own value ({@code 0.001} = 0.1%). A one-sided wall
 * never has a mirror, so it is rejected only by a twin.
 *
 * <p>Reads the current book only, with no history. Both scans walk a side from the spread outward
 * and stop at the first level past their distance bound, so the cost is bounded by the levels near
 * mid. Hot path: no allocation beyond the {@link TreeMap} iterators, which the classifier's own
 * scan uses too.
 *
 * @param twinMaxDistance         fraction of mid within which a same-side twin counts
 * @param twinQuantityTolerance   relative quantity difference that still counts as a twin
 * @param mirrorNotionalTolerance relative notional difference that still counts as a mirror
 * @param mirrorDistanceTolerance relative distance-from-mid difference that still counts as a mirror
 */
public record MmFingerprintFilter(
        double twinMaxDistance,
        double twinQuantityTolerance,
        double mirrorNotionalTolerance,
        double mirrorDistanceTolerance
) implements LevelFilter {

    @Override
    public boolean accept(Map.Entry<Double, PriceLevelEntry> level, boolean isBid, OrderBook book, long nowMillis) {
        return !hasTwin(level.getValue(), isBid ? book.getBids() : book.getAsks())
                && !hasMirror(level, isBid ? book.getAsks() : book.getBids());
    }

    private boolean hasTwin(PriceLevelEntry level, TreeMap<Double, PriceLevelEntry> side) {
        double maxDiff = level.quantity * twinQuantityTolerance;
        for (PriceLevelEntry other : side.values()) {
            if (other.distance > twinMaxDistance) break;
            if (other != level && Math.abs(other.quantity - level.quantity) <= maxDiff) return true;
        }
        return false;
    }

    private boolean hasMirror(Map.Entry<Double, PriceLevelEntry> level, TreeMap<Double, PriceLevelEntry> otherSide) {
        double distance = level.getValue().distance;
        double minDistance = distance * (1 - mirrorDistanceTolerance);
        double maxDistance = distance * (1 + mirrorDistanceTolerance);
        double notional = level.getKey() * level.getValue().quantity;
        double maxDiff = notional * mirrorNotionalTolerance;
        for (Map.Entry<Double, PriceLevelEntry> other : otherSide.entrySet()) {
            double otherDistance = other.getValue().distance;
            if (otherDistance > maxDistance) break;
            if (otherDistance < minDistance) continue;
            if (Math.abs(other.getKey() * other.getValue().quantity - notional) <= maxDiff) return true;
        }
        return false;
    }
}
