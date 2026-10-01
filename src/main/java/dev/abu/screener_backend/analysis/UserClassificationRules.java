package dev.abu.screener_backend.analysis;

import java.util.Map;
import java.util.Set;

/**
 * A per-user lookup table of {@code (symbol, market)} override rules, built off the hot path from
 * a user's persisted tier rows at WebSocket connect time (Phase C).
 *
 * <p>Keys are {@link dev.abu.screener_backend.exchange.Instrument#ruleKey() ruleKey}s —
 * {@code "BASEQUOTE:MARKET"}, e.g. {@code "BTCUSDT:SPOT"}. They carry no exchange, so one rule
 * applies to that symbol and market on every exchange.
 *
 * <p>It intentionally does <b>not</b> implement {@link ClassificationRule}. The classifier fetches
 * the per-key leaf via {@link #ruleFor(String)} (a {@code null} means "not configured") and passes
 * that leaf to its top-K selection; the broadcaster uses {@link #configuredKeys()} to filter the
 * global feed. Keys absent from the map are never touched by the user classification pass — the
 * user receives the global/default classification for them via the broadcaster merge.
 *
 * <p>Immutable after construction; safe to publish across threads via the {@code volatile}
 * active-context array.
 */
public final class UserClassificationRules {

    private final Map<String, ThresholdClassificationRule> byKey; // key = ruleKey, "BASEQUOTE:MARKET"
    private final Set<String> configuredKeys;                     // = byKey.keySet(), cached

    public UserClassificationRules(Map<String, ThresholdClassificationRule> byKey) {
        this.byKey = byKey;
        this.configuredKeys = byKey.keySet();
    }

    /** O(1) hot-path membership check — the set of {@code ruleKey}s this user configured. */
    public Set<String> configuredKeys() {
        return configuredKeys;
    }

    /** The override leaf for a configured key, or {@code null} if the key is not configured. */
    public ThresholdClassificationRule ruleFor(String key) {
        return byKey.get(key);
    }
}
