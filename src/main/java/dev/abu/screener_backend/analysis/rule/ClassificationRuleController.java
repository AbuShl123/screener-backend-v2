package dev.abu.screener_backend.analysis.rule;

import dev.abu.screener_backend.analysis.DefaultClassificationRule;
import dev.abu.screener_backend.analysis.rule.dto.BulkDeleteRequest;
import dev.abu.screener_backend.analysis.rule.dto.BulkRuleRequest;
import dev.abu.screener_backend.analysis.rule.dto.DefaultRuleResponse;
import dev.abu.screener_backend.analysis.rule.dto.RuleResponse;
import dev.abu.screener_backend.auth.AuthService;
import dev.abu.screener_backend.auth.AuthenticatedUser;
import dev.abu.screener_backend.binance.websocket.Market;
import dev.abu.screener_backend.entitlement.EntitlementService;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.user.User;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Per-user classification rule management.
 *
 * <p>Mounted under {@code /api/rules}. All endpoints are covered by the
 * {@code .anyRequest().authenticated()} catch-all in {@code SecurityConfig} (only {@code /api/auth/*}
 * and {@code /ws} are public), so every call requires a Bearer JWT. The {@code userId} always comes
 * from the JWT principal, never the request body, so a user can only ever see or modify their own
 * rules.
 */
@RestController
@RequestMapping("/api/rules")
public class ClassificationRuleController {

    private final ClassificationRuleService ruleService;
    private final DefaultClassificationRule defaultRule;
    private final AuthService authService;
    private final EntitlementService entitlementService;

    public ClassificationRuleController(ClassificationRuleService ruleService,
                                        DefaultClassificationRule defaultRule,
                                        AuthService authService,
                                        EntitlementService entitlementService) {
        this.ruleService = ruleService;
        this.defaultRule = defaultRule;
        this.authService = authService;
        this.entitlementService = entitlementService;
    }

    @GetMapping("/default")
    public DefaultRuleResponse getDefaultRule() {
        return defaultRule.toResponse();
    }

    @GetMapping
    public List<RuleResponse> getRules(Authentication authentication) {
        return ruleService.getRules(authorizedUserId(authentication));
    }

    @GetMapping("/{symbol}/{market}")
    public RuleResponse getRule(Authentication authentication,
                                @PathVariable String symbol,
                                @PathVariable String market) {
        return ruleService.getRule(authorizedUserId(authentication), symbol, parseMarket(market));
    }

    @PutMapping
    public void upsertRules(Authentication authentication, @RequestBody BulkRuleRequest req) {
        ruleService.upsertRules(authorizedUserId(authentication), req);
    }

    @DeleteMapping
    public void deleteRules(Authentication authentication, @RequestBody BulkDeleteRequest req) {
        ruleService.deleteRules(authorizedUserId(authentication), req);
    }

    private static UUID userId(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }

    private UUID authorizedUserId(Authentication authentication) {
        UUID userId = userId(authentication);
        User user = authService.getUser(userId);
        if (!entitlementService.hasAccess(user)) {
            throw ApiException.forbidden("Active subscription required");
        }
        return userId;
    }

    /** Parse a path-variable market case-insensitively, returning 400 on an unknown value. */
    private static Market parseMarket(String market) {
        try {
            return Market.valueOf(market.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown market: " + market);
        }
    }
}
