package dev.abu.screener_backend.analysis.rule;

import dev.abu.screener_backend.analysis.DefaultClassificationRule;
import dev.abu.screener_backend.analysis.rule.dto.BulkDeleteRequest;
import dev.abu.screener_backend.analysis.rule.dto.BulkRuleRequest;
import dev.abu.screener_backend.analysis.rule.dto.DefaultRuleResponse;
import dev.abu.screener_backend.analysis.rule.dto.RuleResponse;
import dev.abu.screener_backend.auth.AuthenticatedUser;
import dev.abu.screener_backend.binance.websocket.Market;
import dev.abu.screener_backend.error.ApiException;
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

    public ClassificationRuleController(ClassificationRuleService ruleService,
                                        DefaultClassificationRule defaultRule) {
        this.ruleService = ruleService;
        this.defaultRule = defaultRule;
    }

    @GetMapping("/default")
    public DefaultRuleResponse getDefaultRule() {
        return defaultRule.toResponse();
    }

    @GetMapping
    public List<RuleResponse> getRules(Authentication authentication) {
        return ruleService.getRules(userId(authentication));
    }

    @GetMapping("/{symbol}/{market}")
    public RuleResponse getRule(Authentication authentication,
                                @PathVariable String symbol,
                                @PathVariable String market) {
        return ruleService.getRule(userId(authentication), symbol, parseMarket(market));
    }

    @PutMapping
    public void upsertRules(Authentication authentication, @RequestBody BulkRuleRequest req) {
        ruleService.upsertRules(userId(authentication), req);
    }

    @DeleteMapping
    public void deleteRules(Authentication authentication, @RequestBody BulkDeleteRequest req) {
        ruleService.deleteRules(userId(authentication), req);
    }

    private static UUID userId(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
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
