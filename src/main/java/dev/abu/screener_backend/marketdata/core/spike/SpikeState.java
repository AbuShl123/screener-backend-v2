package dev.abu.screener_backend.marketdata.core.spike;

/**
 * One instrument's spike re-arm state. Lives in the slot; only the instrument's shard thread
 * touches it.
 */
public final class SpikeState {

    /**
     * Highest pump level already alerted, {@code floor(pct / threshold)}; {@code 0} when armed. A
     * pump alerts again only when it reaches a higher level.
     */
    int pumpLevel;
    /** The same for dumps; pumps and dumps re-arm independently. */
    int dumpLevel;
    /** {@code false} after a volume alert, until the increase falls back below the threshold. */
    boolean volumeArmed = true;

    public int pumpLevel() {
        return pumpLevel;
    }

    public int dumpLevel() {
        return dumpLevel;
    }

    public boolean volumeArmed() {
        return volumeArmed;
    }
}
