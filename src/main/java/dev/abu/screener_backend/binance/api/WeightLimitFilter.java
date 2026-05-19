package dev.abu.screener_backend.binance.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * WebClient filter that enforces Binance API weight limits.
 *
 * <p>Before each request the {@link WeightGuard} is consulted. If the previous response
 * indicated that the weight budget is nearly exhausted, the request is held via
 * {@link Mono#delay} until the wall-clock minute boundary resets the counter.
 *
 * <p>After each response the {@code x-mbx-used-weight-1m} weight and the HTTP {@code Date}
 * header (Binance server send time) are extracted and fed to the guard. Using the server
 * send time rather than local receive time keeps minute-boundary detection accurate even
 * under network latency.
 */
@Slf4j
public class WeightLimitFilter implements ExchangeFilterFunction {

    private static final String WEIGHT_HEADER = "x-mbx-used-weight-1m";

    private final WeightGuard guard;
    private final String market;

    public WeightLimitFilter(WeightGuard guard, String market) {
        this.guard = guard;
        this.market = market;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        long delayMs = guard.delayMillisRequired();
        Mono<ClientResponse> call = next.exchange(request).doOnNext(this::observeWeight);
        if (delayMs > 0) {
            log.warn("[{}] Weight limit approached — delaying next request by {} ms", market, delayMs);
            return Mono.delay(Duration.ofMillis(delayMs)).then(call);
        }
        return call;
    }

    private void observeWeight(ClientResponse response) {
        HttpHeaders headers = response.headers().asHttpHeaders();
        String rawWeight = headers.getFirst(WEIGHT_HEADER);
        if (rawWeight == null) return;

        try {
            long weight = Long.parseLong(rawWeight);
            long sentTimeMs = headers.getFirstDate(HttpHeaders.DATE);
            if (sentTimeMs == -1) {
                sentTimeMs = System.currentTimeMillis();
            }
            guard.observe(sentTimeMs, weight);
        } catch (NumberFormatException e) {
            log.warn("[{}] Unparseable {} header value: '{}'", market, WEIGHT_HEADER, rawWeight);
        }
    }
}
