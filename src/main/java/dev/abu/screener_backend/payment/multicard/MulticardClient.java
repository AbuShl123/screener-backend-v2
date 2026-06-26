package dev.abu.screener_backend.payment.multicard;

import dev.abu.screener_backend.config.PaymentProperties;
import dev.abu.screener_backend.config.PaymentProperties.MulticardProperties;
import dev.abu.screener_backend.payment.multicard.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Thin blocking wrapper around the Multicard REST API (mirrors {@code BinanceRestClient}'s style —
 * low-frequency calls, {@code .block()} is fine).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><strong>Token cache</strong>: {@code POST /auth} lazily, reuse the Bearer token until near
 *       expiry; on a {@code 401}, refetch once and retry the call.</li>
 *   <li>{@code createInvoice}, {@code getPayment}, {@code cancelInvoice} — unwrap the
 *       {@code {success, data}} / {@code {success, error}} envelope, throwing {@link MulticardException}
 *       on {@code success=false}.</li>
 * </ul>
 */
@Component
@Slf4j
public class MulticardClient {

    /** Conservative token TTL — provider tokens last ~24h; we refetch well before that (and on any 401). */
    private static final Duration TOKEN_TTL = Duration.ofHours(23);

    private final WebClient webClient;
    private final MulticardProperties props;
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    public MulticardClient(@Qualifier("multicardWebClient") WebClient webClient, PaymentProperties props) {
        this.webClient = webClient;
        this.props = props.multicard();
    }

    /** Creates a hosted-checkout invoice; returns the {@code data} block (uuid + checkout_url). */
    public MulticardInvoiceResponse.Data createInvoice(MulticardInvoiceRequest request) {
        MulticardInvoiceResponse response = withAuthRetry(token -> webClient.post()
                .uri("/payment/invoice")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .bodyValue(request)
                .retrieve()
                .bodyToMono(MulticardInvoiceResponse.class)
                .block());
        if (response == null || !Boolean.TRUE.equals(response.success()) || response.data() == null) {
            throw envelopeError("invoice creation", response == null ? null : response.error());
        }
        return response.data();
    }

    /** Durable status fetch for reconciliation: {@code GET /payment/{uuid}}. */
    public MulticardPaymentResponse.Data getPayment(String uuid) {
        MulticardPaymentResponse response = withAuthRetry(token -> webClient.get()
                .uri("/payment/{uuid}", uuid)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .bodyToMono(MulticardPaymentResponse.class)
                .block());
        if (response == null || !Boolean.TRUE.equals(response.success()) || response.data() == null) {
            throw envelopeError("payment fetch", response == null ? null : response.error());
        }
        return response.data();
    }

    /** Cancels an unpaid invoice when superseding an open order. Best-effort: logs and swallows failures. */
    public void cancelInvoice(String uuid) {
        try {
            withAuthRetry(token -> webClient.delete()
                    .uri("/payment/invoice/{uuid}", uuid)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block());
        } catch (RuntimeException e) {
            // The invoice may already be completed/expired (ERROR_FIELDS / ERROR_TRANS_NOT_READY) — not fatal.
            log.warn("Multicard invoice cancel failed for {}: {}", uuid, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Auth + token cache
    // ---------------------------------------------------------------------------------------

    private <T> T withAuthRetry(Function<String, T> call) {
        try {
            return call.apply(currentToken());
        } catch (WebClientResponseException.Unauthorized e) {
            log.info("Multicard token rejected (401); refetching and retrying once");
            return call.apply(refreshToken());
        }
    }

    private String currentToken() {
        CachedToken token = cachedToken.get();
        if (token != null && Instant.now().isBefore(token.expiresAt())) {
            return token.value();
        }
        return refreshToken();
    }

    private synchronized String refreshToken() {
        // Double-check: another thread may have just refreshed.
        CachedToken existing = cachedToken.get();
        if (existing != null && Instant.now().isBefore(existing.expiresAt())) {
            return existing.value();
        }
        MulticardAuthResponse auth = webClient.post()
                .uri("/auth")
                .bodyValue(Map.of(
                        "application_id", nullToEmpty(props.applicationId()),
                        "secret", nullToEmpty(props.secret())))
                .retrieve()
                .bodyToMono(MulticardAuthResponse.class)
                .block();
        if (auth == null || auth.token() == null || auth.token().isBlank()) {
            throw new MulticardException("Multicard auth returned no token", (String) null);
        }
        CachedToken fresh = new CachedToken(auth.token(), Instant.now().plus(TOKEN_TTL));
        cachedToken.set(fresh);
        return fresh.value();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static MulticardException envelopeError(String op, MulticardError error) {
        if (error != null) {
            return new MulticardException("Multicard " + op + " failed: " + error.details(), error.code());
        }
        return new MulticardException("Multicard " + op + " failed: empty/unsuccessful response", (String) null);
    }

    /** Cached Bearer token with a local expiry. */
    private record CachedToken(String value, Instant expiresAt) {}
}
