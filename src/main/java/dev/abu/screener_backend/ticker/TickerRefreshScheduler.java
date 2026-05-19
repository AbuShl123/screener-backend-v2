package dev.abu.screener_backend.ticker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes the ticker registry on a configurable interval.
 *
 * <p>Uses {@code fixedDelayString} (not {@code fixedRateString}) so that the next refresh
 * starts only after the previous one has fully completed — preventing overlapping fetches
 * during slow network conditions or extended Binance response times.
 *
 * <p>The interval is configured via {@code screener.ticker.refresh-interval} in
 * {@code application.yml} and accepts ISO-8601 duration strings (e.g. {@code PT4H}).
 * {@code @EnableScheduling} on {@link dev.abu.screener_backend.ScreenerBackendApplication}
 * is required for this annotation to take effect.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TickerRefreshScheduler {

    private final TickerService tickerService;

    /**
     * Executes a ticker refresh. The next execution begins
     * {@code screener.ticker.refresh-interval} after this method returns.
     */
    @Scheduled(fixedDelayString = "${screener.ticker.refresh-interval}")
    public void scheduledRefresh() {
        log.info("Scheduled ticker refresh triggered");
        tickerService.refreshTickers();
    }
}
