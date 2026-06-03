package dev.abu.screener_backend.analysis;

import java.util.Map;
import java.util.Set;

/**
 * A per-user lookup table of {@code (symbol, market)} override rules, built off the hot path from
 * a user's persisted tier rows at WebSocket connect time (Phase C).
 *
 * <p>It intentionally does <b>not</b> implement {@link ClassificationRule}. The classifier checks
 * {@link #configuredKeys()} for an O(1) membership test, then fetches the per-key leaf via
 * {@link #ruleFor(String)} and passes that leaf to its top-K selection. Keys absent from the map
 * are never touched by the user classification pass — the user receives the global/default
 * classification for them via the broadcaster merge.
 *
 * <p>Immutable after construction; safe to publish across threads via the {@code volatile}
 * active-context array.
 */
public final class UserClassificationRule {

    private final Map<String, ThresholdClassificationRule> byKey; // key = "SYMBOL:MARKET"
    private final Set<String> configuredKeys;                     // = byKey.keySet(), cached

    public UserClassificationRule(Map<String, ThresholdClassificationRule> byKey) {
        this.byKey = byKey;
        this.configuredKeys = byKey.keySet();
    }

    /** O(1) hot-path membership check — the set of {@code "SYMBOL:MARKET"} keys this user configured. */
    public Set<String> configuredKeys() {
        return configuredKeys;
    }

    /** The override leaf for a configured key, or {@code null} if the key is not configured. */
    public ThresholdClassificationRule ruleFor(String key) {
        return byKey.get(key);
    }
}
