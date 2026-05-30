package dev.abu.screener_backend.analysis.rule.dto;

/**
 * One tier of a classification rule.
 *
 * @param tier        tier number, 1..4 (validated in the service)
 * @param minNotional minimum order notional in USD to qualify for this tier; {@code >= 0}
 * @param maxDistance maximum fractional distance from mid-price; {@code (0, price-filter-threshold]}
 */
public record TierDto(int tier, double minNotional, double maxDistance) {}
