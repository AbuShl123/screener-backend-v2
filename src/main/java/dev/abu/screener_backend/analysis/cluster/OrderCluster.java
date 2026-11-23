package dev.abu.screener_backend.analysis.cluster;

/**
 * One announced layered order cluster: a run of consecutive, similar price levels on one side of
 * one book that together form a wall. Built on the shard thread at emit time, then immutable and
 * safe to hand to any thread.
 *
 * <p>The ladder is two parallel arrays ordered from {@code priceFrom} to {@code priceTo} (ascending
 * price, whatever the side). They are owned by this record and never written after construction;
 * readers must not modify them either. Equality is identity for the arrays, as for any record.
 *
 * @param priceFrom       lowest price in the cluster
 * @param priceTo         highest price in the cluster
 * @param levelCount      number of levels, {@code prices.length}
 * @param totalNotional   sum of {@code price × quantity}, quote currency
 * @param totalQuantity   sum of quantities, base asset
 * @param distance        fractional distance of the edge nearest the mid from the mid (0.031 = 3.1%)
 * @param firstSeenMillis when the detector first saw this cluster, epoch millis (wall clock), kept across updates
 * @param prices          level prices, ascending
 * @param quantities      level quantities, aligned with {@code prices}
 */
public record OrderCluster(
        double priceFrom,
        double priceTo,
        int levelCount,
        double totalNotional,
        double totalQuantity,
        double distance,
        long firstSeenMillis,
        double[] prices,
        double[] quantities
) {
}
