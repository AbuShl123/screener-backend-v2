package dev.abu.screener_backend.exchange.rest;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.Arrays;

/**
 * Builds a {@link WebClient} for one venue's REST API from its {@code rest} config block.
 *
 * <p>The response timeout is a reactor-netty network timeout
 * ({@link HttpClient#responseTimeout}), never {@code Mono.timeout} — the latter would also count
 * time a request spends waiting on a request budget, understating how long the network call
 * itself took.
 *
 * <p>Adapter-supplied filters, if any, are appended in the given order; this factory does not know
 * what they do. Binance passes none: its weight accounting lives in its snapshot fetcher.
 */
@Component
public class ExchangeWebClientFactory {

    public WebClient create(RestProperties rest, ExchangeFilterFunction... filters) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(config -> config.defaultCodecs()
                        .maxInMemorySize(rest.codecBufferSizeMb() * 1024 * 1024))
                .build();

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) rest.connectTimeout().toMillis())
                .responseTimeout(rest.responseTimeout());

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(rest.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        for (ExchangeFilterFunction filter : filters) {
            builder.filter(filter);
        }

        return builder.build();
    }
}
