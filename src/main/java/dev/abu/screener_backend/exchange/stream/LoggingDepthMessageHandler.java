package dev.abu.screener_backend.exchange.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
// @Component — deactivated in Phase 2; DisruptorDepthMessageHandler is the active bean
public class LoggingDepthMessageHandler implements RawDepthMessageHandler {

    private final AtomicLong messageCount = new AtomicLong();

    @Override
    public void handle(int instrumentId, String rawJson) {
        messageCount.incrementAndGet();
    }

    @Scheduled(fixedDelay = 10_000)
    public void logStats() {
        log.info("Depth messages received: {}", messageCount.getAndSet(0));
    }
}
