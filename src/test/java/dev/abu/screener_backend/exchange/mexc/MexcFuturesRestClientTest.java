package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.exchange.mexc.dto.MexcContractDto;
import dev.abu.screener_backend.exchange.rest.ExchangeApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link MexcFuturesRestClient}'s envelope handling, driven through a real WebClient and JSON decoder. */
class MexcFuturesRestClientTest {

    private final AtomicReference<URI> sent = new AtomicReference<>();

    private MexcFuturesRestClient client(ClientResponse response) {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.mexc.com")
                .exchangeFunction(request -> {
                    sent.set(request.url());
                    return Mono.just(response);
                })
                .build();
        return new MexcFuturesRestClient(webClient);
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Test
    @DisplayName("contractDetail: unwraps data from a successful envelope")
    void contractDetailUnwraps() {
        List<MexcContractDto> contracts = client(json(HttpStatus.OK, """
                {"success":true,"code":0,"data":[{"symbol":"BTC_USDT","baseCoin":"BTC","quoteCoin":"USDT",
                 "futureType":1,"state":0,"apiAllowed":true,"contractSize":0.0001,"maxLeverage":500}]}
                """)).contractDetail().block();

        assertEquals("https://api.mexc.com/api/v1/contract/detail", sent.get().toASCIIString());
        assertEquals(List.of(new MexcContractDto("BTC_USDT", "BTC", "USDT", 1, 0, true, 0.0001)), contracts);
    }

    @Test
    @DisplayName("contractDetail: a throttled HTTP 200 (success:false, code 510) is a MexcApiException, not data")
    void contractDetailThrottled() {
        MexcFuturesRestClient client = client(json(HttpStatus.OK,
                "{\"success\":false,\"code\":510,\"message\":\"Requests are too frequent, please try again later\"}"));

        MexcApiException e = assertThrows(MexcApiException.class, () -> client.contractDetail().block());

        assertEquals(510, e.getCode());
    }

    @Test
    @DisplayName("contractDetail: success with no data is a changed shape and fails")
    void contractDetailNoData() {
        MexcFuturesRestClient client = client(json(HttpStatus.OK, "{\"success\":true,\"code\":0}"));

        assertThrows(IllegalStateException.class, () -> client.contractDetail().block());
    }

    @Test
    @DisplayName("contractDetail: an Akamai HTML 403 is an ExchangeApiException carrying the status")
    void contractDetailWafBlock() {
        MexcFuturesRestClient client = client(ClientResponse.create(HttpStatus.FORBIDDEN)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                .body("<HTML><HEAD><TITLE>Access Denied</TITLE></HEAD></HTML>")
                .build());

        ExchangeApiException e = assertThrows(ExchangeApiException.class, () -> client.contractDetail().block());

        assertEquals(403, e.getStatusCode().value());
    }
}
