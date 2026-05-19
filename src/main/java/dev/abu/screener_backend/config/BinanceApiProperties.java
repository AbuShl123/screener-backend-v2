package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binance REST API connection properties.
 * Bound from the {@code screener.binance} prefix in {@code application.yml}.
 *
 * @param spotBaseUrl              base URL for the Binance Spot REST API
 * @param futuresBaseUrl           base URL for the Binance Futures REST API
 * @param codecBufferSizeMb        maximum in-memory buffer size (MB) for WebClient response codecs;
 *                                 must be large enough to hold a full exchangeInfo response (~1–2 MB)
 * @param spotWeightThreshold      weight usage level at which spot requests are held until the
 *                                 next minute boundary (Binance hard limit: 6 000)
 * @param futuresWeightThreshold   weight usage level at which futures requests are held until the
 *                                 next minute boundary (Binance hard limit: 2 400)
 */
@ConfigurationProperties(prefix = "screener.binance")
public record BinanceApiProperties(
        String spotBaseUrl,
        String futuresBaseUrl,
        int codecBufferSizeMb,
        long spotWeightThreshold,
        long futuresWeightThreshold
) {}
