package dev.abu.screener_backend.binance.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
// @Component — deactivated in Phase 2; DisruptorDepthMessageHandler is the active bean
public class LoggingDepthMessageHandler implements RawDepthMessageHandler {

    private final AtomicLong spotCount = new AtomicLong();
    private final AtomicLong futuresCount = new AtomicLong();

    @Override
    public void handle(String symbol, Market market, String rawJson) {
        if (market == Market.SPOT) spotCount.incrementAndGet();
        else futuresCount.incrementAndGet();
    }

    @Scheduled(fixedDelay = 10_000)
    public void logStats() {
        log.info("Depth messages received — spot: {}, futures: {}",
                spotCount.getAndSet(0), futuresCount.getAndSet(0));
    }
}
