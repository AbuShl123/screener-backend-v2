package dev.abu.screener_backend.config;

import dev.abu.screener_backend.binance.api.WeightGuard;
import dev.abu.screener_backend.binance.api.WeightLimitFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configures {@link WebClient} beans for Binance REST API communication.
 *
 * <p>Two named clients are provided — one for the Spot API and one for the Futures API —
 * each pre-configured with the appropriate base URL and a generous in-memory codec buffer.
 *
 * <h3>Why the codec buffer must be enlarged</h3>
 * Binance's {@code /exchangeInfo} endpoint returns 1–2 MB of JSON covering all listed symbols.
 * The default WebClient codec buffer (256 KB) throws {@link reactor.core.publisher.Mono}
 * {@code DataBufferLimitException} without this override.
 *
 * <h3>Why {@code spring.main.web-application-type: servlet} is required</h3>
 * Adding {@code spring-boot-starter-webflux} alongside {@code spring-boot-starter-webmvc}
 * causes Spring Boot to start a Netty reactive server instead of Tomcat. The property
 * in {@code application.yml} forces servlet mode so the application remains on Spring MVC.
 */
@Configuration
@EnableConfigurationProperties({BinanceApiProperties.class, WebSocketProperties.class, DisruptorProperties.class, OrderbookProperties.class, JwtProperties.class})
public class WebClientConfig {

    /**
     * WebClient pre-configured for the Binance Spot REST API.
     *
     * @param props Binance API connection properties
     * @return spot WebClient bean
     */
    @Bean("spotWebClient")
    public WebClient spotWebClient(BinanceApiProperties props) {
        WeightLimitFilter filter = new WeightLimitFilter(new WeightGuard(props.spotWeightThreshold()), "SPOT");
        return buildWebClient(props.spotBaseUrl(), props.codecBufferSizeMb(), filter);
    }

    /**
     * WebClient pre-configured for the Binance Futures REST API.
     *
     * @param props Binance API connection properties
     * @return futures WebClient bean
     */
    @Bean("futuresWebClient")
    public WebClient futuresWebClient(BinanceApiProperties props) {
        WeightLimitFilter filter = new WeightLimitFilter(new WeightGuard(props.futuresWeightThreshold()), "FUTURES");
        return buildWebClient(props.futuresBaseUrl(), props.codecBufferSizeMb(), filter);
    }

    private WebClient buildWebClient(String baseUrl, int codecBufferSizeMb, WeightLimitFilter weightFilter) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(config -> config.defaultCodecs()
                        .maxInMemorySize(codecBufferSizeMb * 1024 * 1024))
                .build();
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .exchangeStrategies(strategies)
                .filter(weightFilter)
                .build();
    }
}
