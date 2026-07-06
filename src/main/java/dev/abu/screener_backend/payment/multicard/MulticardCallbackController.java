package dev.abu.screener_backend.payment.multicard;

import dev.abu.screener_backend.payment.multicard.dto.MulticardCallbackPayload;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public Multicard success-callback endpoint. Not JWT-protected — it is secured by signature + source
 * IP (verified in {@link MulticardCallbackService}). Server-to-server, so no CORS/session concerns.
 *
 * <p>The {@link CallbackOutcome} → HTTP mapping is the contract Multicard relies on:
 * {@code 200 {"success":true}} confirms; {@code 200 {"success":false,...}} triggers a refund;
 * {@code 400} is treated as unprocessed; {@code 500} freezes funds and retries.
 */
@RestController
@RequestMapping("/api/payment/multicard/callback")
@Slf4j
public class MulticardCallbackController {

    private final MulticardCallbackService callbackService;

    public MulticardCallbackController(MulticardCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> callback(@RequestBody MulticardCallbackPayload payload,
                                                        HttpServletRequest request) {
        String sourceIp = resolveSourceIp(request);
        CallbackOutcome outcome = callbackService.handle(payload, sourceIp);
        return switch (outcome.kind()) {
            case OK -> ResponseEntity.ok(Map.of("success", true));
            case REJECT -> ResponseEntity.ok(body(false, outcome.reason().getDescription()));
            case REJECT_BAD_SIGN, REJECT_BAD_SOURCE -> ResponseEntity.badRequest().body(body(false, "Rejected"));
            case RETRY -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(false, "Temporary error"));
        };
    }

    private static Map<String, Object> body(boolean success, String message) {
        // Ordered map so "success" reads first; LinkedHashMap tolerates the (never-null here) message.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", success);
        body.put("message", message);
        return body;
    }

    /**
     * The callback's source IP. Behind a reverse proxy the real client IP is the first hop of
     * {@code X-Forwarded-For}; without a proxy header we fall back to the socket address.
     *
     * <p><strong>SECURITY CAVEAT (recorded — not fixed):</strong> trusting the first
     * {@code X-Forwarded-For} hop is only safe if every request reaches us <em>through</em> the trusted
     * proxy. If the app is also reachable directly (bypassing the proxy), a client can forge
     * {@code X-Forwarded-For: 195.158.26.90} and defeat the IP allow-list — leaving only the shared-secret
     * MD5 signature as protection. Before exposing this callback outside a trusted-proxy deployment, switch
     * to a vetted forwarded-header strategy (e.g. Spring's {@code ForwardedHeaderFilter} +
     * {@code server.forward-headers-strategy}) with a trusted-proxy allow-list.
     */
    private static String resolveSourceIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
