package dev.abu.screener_backend.marketdata;

/**
 * Outcome of the most recent instrument-discovery attempt for one {@link Venue}.
 *
 * <p>Describes the instrument universe only — it says nothing about the venue's WebSocket
 * connections or order-book sync health.
 */
public enum DiscoveryStatus {
    /** No discovery attempt has completed for the venue yet. */
    PENDING,
    /** The last discovery attempt succeeded. */
    OK,
    /**
     * The last discovery attempt failed; the venue keeps its previous universe, which is empty if
     * no attempt has ever succeeded.
     */
    RETAINED
}
