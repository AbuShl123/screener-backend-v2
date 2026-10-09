package dev.abu.screener_backend.marketdata.adapter.mexc;

/**
 * The sequence rule both MEXC venues share. Every push covers a contiguous version range
 * ({@code begin} / {@code end} on futures, {@code fromVersion} / {@code toVersion} on spot), the
 * ranges of consecutive pushes are contiguous, and a REST snapshot's version is on the same counter
 * but not aligned to push boundaries ({@code external-docs/mexc/mexc-depth-versioning-empirical.md},
 * {@code mexc-spot-depth-empirical.md} §2). One predicate therefore both finds the post-snapshot
 * sync point and validates every push after it.
 */
final class MexcVersionRange {

    enum CheckResult {
        OK, IGNORE, DE_SYNCED
    }

    private MexcVersionRange() {
    }

    /**
     * Validates one push's {@code [begin, end]} range against the book's cursor.
     *
     * <pre>
     * end   &lt;  lastVersion       → IGNORE     (wholly covered by what the book has)
     * begin &lt;= lastVersion + 1   → OK         (contiguous or overlapping; cursor → end)
     * otherwise                  → DE_SYNCED  (a gap)
     * </pre>
     *
     * {@code OK} holds exactly when {@code lastVersion ∈ [begin - 1, end]}. Accepting an overlap is
     * safe because quantities are absolute and an accepted push has {@code end >= lastVersion}, so
     * its values are at least as new as the book's. The strict {@code <} keeps a push whose
     * {@code end} equals the snapshot's version — the same choice as Binance spot (decision 8 in the
     * progress doc). The snapshot version is inclusive on both MEXC venues, so re-applying that push
     * is harmless.
     *
     * <p>Pure: on {@code OK} the caller moves its cursor to {@code end}.
     */
    static CheckResult check(long begin, long end, long lastVersion) {
        if (end < lastVersion) {
            return CheckResult.IGNORE;
        } else if (begin <= lastVersion + 1) {
            return CheckResult.OK;
        } else {
            return CheckResult.DE_SYNCED;
        }
    }
}
