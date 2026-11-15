package dev.abu.screener_backend.marketdata.adapter.bybit;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * The pause on Bybit snapshot requests after Bybit pushes back. One instance shared by both venues'
 * {@link BybitSnapshotFetcher}s: the REST limit (600 requests per 5 s) is per IP, across every
 * category and every Bybit host, and breaking it bans the IP for 10 minutes with HTTP 403
 * ({@code external-docs/bybit/bybit-api-contracts.md} §3.6). A pushback seen by one venue must stop
 * the other too.
 *
 * <p>Built once by {@code BybitAdapterConfig}. Time is passed in by the caller, so the fetchers'
 * scheduler stays the only clock.
 */
@Slf4j
public class BybitRestCooldown {

    enum Kind {
        /** A {@code retCode} 10006 / 10018 in an HTTP 200, or an HTTP 429: over the window, not yet banned. */
        RATE_LIMITED,
        /** An HTTP 403: Bybit's 10-minute IP ban, or a geo-block. */
        BANNED
    }

    private final long rateLimitedMs;
    private final long bannedMs;

    /** Read by shard consumer threads; written under {@code this}. */
    private volatile long untilMs;
    /** The pause in force, for logging only. Guarded by {@code this}. */
    private Kind kind;

    /** @throws IllegalArgumentException if a duration is not positive */
    public BybitRestCooldown(Duration rateLimited, Duration banned) {
        requirePositive(rateLimited, "rate-limited");
        requirePositive(banned, "banned");
        this.rateLimitedMs = rateLimited.toMillis();
        this.bannedMs = banned.toMillis();
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || !value.isPositive()) {
            throw new IllegalArgumentException("snapshot-cooldown." + name + " must be positive");
        }
    }

    /** One volatile read. */
    public boolean isActive(long nowMs) {
        return nowMs < untilMs;
    }

    /** Milliseconds left, for logging; {@code <= 0} once the pause is over. */
    long remainingMs(long nowMs) {
        return untilMs - nowMs;
    }

    /**
     * Starts or extends a pause; never shortens one, so a short rate-limit pause never cuts a ban
     * short. Logged at WARN when it starts a pause or escalates a rate-limit pause to a ban, at debug
     * otherwise.
     *
     * @param cause what Bybit answered, and on which venue
     */
    public void start(Kind kind, long nowMs, String cause) {
        long durationMs = kind == Kind.RATE_LIMITED ? rateLimitedMs : bannedMs;
        boolean escalated;
        long effectiveUntilMs;
        synchronized (this) {
            boolean inForce = nowMs < untilMs;
            escalated = !inForce || (kind == Kind.BANNED && this.kind == Kind.RATE_LIMITED);
            if (nowMs + durationMs > untilMs) {
                untilMs = nowMs + durationMs;
                this.kind = kind;
            }
            effectiveUntilMs = untilMs;
        }
        if (escalated) {
            log.warn("{} - Bybit snapshot requests paused on both venues for {} ms, until {}",
                    cause, durationMs, Instant.ofEpochMilli(effectiveUntilMs));
        } else {
            log.debug("{} - already paused until {}", cause, Instant.ofEpochMilli(effectiveUntilMs));
        }
    }
}
