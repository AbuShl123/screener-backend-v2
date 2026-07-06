package dev.abu.screener_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables {@code @Async} and provides a small, dedicated executor for outbound email so blocking SMTP
 * round-trips never borrow the common {@code ForkJoinPool} or stall Tomcat/Disruptor threads. Entirely
 * separate from the market-data pipeline — no throughput impact.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("emailExecutor")
    public Executor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("email-");
        executor.initialize();
        return executor;
    }
}
