package dev.abu.screener_backend.ticker;

import dev.abu.screener_backend.exchange.InstrumentUniverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes the tracked instrument universe on a configurable interval.
 *
 * <p>The first execution happens at startup, which is what brings the pipeline up: discovery
 * registers instruments, allocates their book slots and publishes the universe-changed event that
 * starts the WebSocket pools.
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

    private final InstrumentUniverseService universeService;

    /**
     * Executes a universe refresh. The next execution begins
     * {@code screener.ticker.refresh-interval} after this method returns.
     */
    @Scheduled(fixedDelayString = "${screener.ticker.refresh-interval}")
    public void scheduledRefresh() {
        log.info("Scheduled instrument universe refresh triggered");
        universeService.refresh();
    }
}
