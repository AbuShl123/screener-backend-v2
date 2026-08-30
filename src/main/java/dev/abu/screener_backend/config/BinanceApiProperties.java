package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binance REST request-weight limits.
 * Bound from the {@code screener.binance} prefix in {@code application.yml}.
 *
 * <p>The base URLs and the codec buffer size that used to live here moved to
 * {@link ExchangesProperties} — they are per-venue and per-exchange respectively. What remains is
 * the weight budget, which is Binance-specific in a deeper way: it is fed by the
 * {@code x-mbx-used-weight-1m} response header and resets on a wall-clock minute boundary, an
 * arrangement most exchanges do not offer. It moves when that becomes a per-venue request budget.
 *
 * @param spotWeightThreshold    weight usage level at which spot requests are held until the
 *                               next minute boundary (Binance hard limit: 6 000)
 * @param futuresWeightThreshold weight usage level at which futures requests are held until the
 *                               next minute boundary (Binance hard limit: 2 400)
 */
@ConfigurationProperties(prefix = "screener.binance")
public record BinanceApiProperties(
        long spotWeightThreshold,
        long futuresWeightThreshold
) {}
