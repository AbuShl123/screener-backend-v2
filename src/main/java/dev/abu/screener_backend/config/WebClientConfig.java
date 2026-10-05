package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configures the non-exchange {@link WebClient} beans. The Binance REST clients live in
 * {@code BinanceAdapterConfig} instead — see {@code ExchangeWebClientFactory}.
 *
 * <h3>Why {@code spring.main.web-application-type: servlet} is required</h3>
 * Adding {@code spring-boot-starter-webflux} alongside {@code spring-boot-starter-webmvc}
 * causes Spring Boot to start a Netty reactive server instead of Tomcat. The property
 * in {@code application.yml} forces servlet mode so the application remains on Spring MVC.
 */
@Configuration
@EnableConfigurationProperties({
        ExchangesProperties.class, DiscoveryProperties.class, WebSocketProperties.class, DisruptorProperties.class,
        OrderbookProperties.class, JwtProperties.class, AdminProperties.class,
        BillingProperties.class, PaymentProperties.class, EmailProperties.class
})
public class WebClientConfig {

    /**
     * WebClient for the Multicard payment gateway REST API. Unlike the Binance clients it has no
     * weight filter — Multicard is low-frequency. The default codec buffer is sufficient (responses
     * are small JSON envelopes).
     *
     * @param props payment properties (Multicard base URL)
     * @return Multicard WebClient bean
     */
    @Bean("multicardWebClient")
    public WebClient multicardWebClient(PaymentProperties props) {
        return WebClient.builder()
                .baseUrl(props.multicard().baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
