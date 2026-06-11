package dev.abu.screener_backend.analysis.rule;

import dev.abu.screener_backend.analysis.ThresholdClassificationRule;
import dev.abu.screener_backend.analysis.UserClassificationRules;
import dev.abu.screener_backend.analysis.rule.dto.*;
import dev.abu.screener_backend.binance.websocket.Market;
import dev.abu.screener_backend.config.OrderbookProperties;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.ticker.Ticker;
import dev.abu.screener_backend.ticker.TickerRegistry;
import dev.abu.screener_backend.user.User;
import dev.abu.screener_backend.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + validation for per-user classification rules (Phase A).
 *
 * <p>Runs entirely on Tomcat request threads and shares no state with the Disruptor pipeline.
 * For Phase A this is purely persistence: nothing here is read on the hot path. The Phase C
 * seam (in-memory rule cache + runtime {@code ClassificationRule} translation) is deliberately
 * not built yet — see {@code per-user-classification-phase-a.md}.
 *
 * <p>All validation runs before any DB write; the whole request is rejected atomically with a
 * {@code 400} on the first failure. No partial application.
 */
@Service
public class ClassificationRuleService {

    private final ClassificationRuleRepository ruleRepository;
    private final UserRepository userRepository;
    private final TickerRegistry tickerRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final double maxDistanceUpperBound;
    private final int maxTargetsPerRequest;

    public ClassificationRuleService(ClassificationRuleRepository ruleRepository,
                                     UserRepository userRepository,
                                     TickerRegistry tickerRegistry,
                                     ApplicationEventPublisher eventPublisher,
                                     OrderbookProperties orderbookProperties,
                                     @Value("${screener.classification.max-targets-per-request:200}")
                                     int maxTargetsPerRequest) {
        this.ruleRepository = ruleRepository;
        this.userRepository = userRepository;
        this.tickerRegistry = tickerRegistry;
        this.eventPublisher = eventPublisher;
        // maxDistance can never usefully exceed the orderbook's price filter: levels beyond it
        // are already swept, so such a rule could never match. (User picked: tie to live config.)
        this.maxDistanceUpperBound = orderbookProperties.priceFilterThreshold();
        this.maxTargetsPerRequest = maxTargetsPerRequest;
    }

    // ---------------------------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------------------------

    @Transactional
    public void upsertRules(UUID userId, BulkRuleRequest req) {
        validate(req);

        // getReferenceById returns a lazy proxy — no extra SELECT just to set the FK.
        User userRef = userRepository.getReferenceById(userId);

        for (RuleAssignmentDto assignment : req.assignments()) {
            for (TargetDto target : assignment.targets()) {
                String symbol = normalizeSymbol(target.symbol());
                Market market = target.market();

                // Replace, not merge: drop the existing tier set, then insert the new one.
                ruleRepository.deleteByUserIdAndSymbolAndMarket(userId, symbol, market);

                for (TierDto tier : assignment.rule().tiers()) {
                    ClassificationRuleEntity entity = new ClassificationRuleEntity();
                    entity.setUser(userRef);
                    entity.setSymbol(symbol);
                    entity.setMarket(market);
                    entity.setTierNo(tier.tier());
                    entity.setMinNotional(tier.minNotional());
                    entity.setMaxDistance(tier.maxDistance());
                    ruleRepository.save(entity);
                }
            }
        }

        // Deferred by @TransactionalEventListener(AFTER_COMMIT) in UserFeedRegistry, so the
        // listener's buildRuntimeRule read sees the committed rows.
        eventPublisher.publishEvent(new RuleUpdatedEvent(userId));
    }

    @Transactional
    public void deleteRules(UUID userId, BulkDeleteRequest req) {
        if (req == null || req.targets() == null || req.targets().isEmpty()) {
            throw badRequest("targets must not be empty");
        }
        if (req.targets().size() > maxTargetsPerRequest) {
            throw badRequest("too many targets: " + req.targets().size()
                    + " (max " + maxTargetsPerRequest + ")");
        }
        for (TargetDto target : req.targets()) {
            requireTarget(target);
            ruleRepository.deleteByUserIdAndSymbolAndMarket(
                    userId, normalizeSymbol(target.symbol()), target.market());
        }

        eventPublisher.publishEvent(new RuleUpdatedEvent(userId));
    }

    // ---------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RuleResponse> getRules(UUID userId) {
        List<ClassificationRuleEntity> rows = ruleRepository.findByUserId(userId);

        // Group tier rows by (symbol, market), preserving a stable insertion order.
        Map<String, List<ClassificationRuleEntity>> grouped = new LinkedHashMap<>();
        for (ClassificationRuleEntity row : rows) {
            grouped.computeIfAbsent(row.getSymbol() + ":" + row.getMarket(), k -> new ArrayList<>())
                    .add(row);
        }

        List<RuleResponse> result = new ArrayList<>(grouped.size());
        for (List<ClassificationRuleEntity> group : grouped.values()) {
            ClassificationRuleEntity first = group.getFirst();
            result.add(new RuleResponse(first.getSymbol(), first.getMarket(), toTierDtos(group)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public RuleResponse getRule(UUID userId, String symbol, Market market) {
        String normalized = normalizeSymbol(symbol);
        List<ClassificationRuleEntity> rows =
                ruleRepository.findByUserIdAndSymbolAndMarket(userId, normalized, market);
        if (rows.isEmpty()) {
            throw ApiException.notFound("No rule for " + normalized + ":" + market);
        }
        return new RuleResponse(normalized, market, toTierDtos(rows));
    }

    // ---------------------------------------------------------------------------------------
    // Hot-path seam: connect-time runtime rule translation
    // ---------------------------------------------------------------------------------------

    /**
     * Translates a user's persisted tier rows into an immutable runtime
     * {@link UserClassificationRules}, called at WebSocket connect time on the Tomcat thread
     * (off the hot path). Returns {@link Optional#empty()} when the user has no rules — those
     * users get no context and consume the global default feed directly.
     *
     * <p>Input was already range-validated at write time (Phase A), so this does not re-validate;
     * it reuses the same {@code "SYMBOL:MARKET"} grouping as {@link #getRules(UUID)}. The stored
     * {@code maxDistance} values are fractions and flow into {@link ThresholdClassificationRule}
     * unchanged.
     */
    @Transactional(readOnly = true)
    public Optional<UserClassificationRules> buildRuntimeRule(UUID userId) {
        List<ClassificationRuleEntity> rows = ruleRepository.findByUserId(userId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, List<ClassificationRuleEntity>> grouped = new LinkedHashMap<>();
        for (ClassificationRuleEntity row : rows) {
            grouped.computeIfAbsent(row.getSymbol() + ":" + row.getMarket(), k -> new ArrayList<>())
                    .add(row);
        }

        Map<String, ThresholdClassificationRule> byKey = new java.util.HashMap<>(grouped.size() * 2);
        for (Map.Entry<String, List<ClassificationRuleEntity>> group : grouped.entrySet()) {
            List<ThresholdClassificationRule.TierThreshold> bands = new ArrayList<>(group.getValue().size());
            for (ClassificationRuleEntity row : group.getValue()) {
                bands.add(new ThresholdClassificationRule.TierThreshold(
                        row.getTierNo(), row.getMinNotional(), row.getMaxDistance()));
            }
            byKey.put(group.getKey(), ThresholdClassificationRule.of(bands));
        }
        return Optional.of(new UserClassificationRules(byKey));
    }

    // ---------------------------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------------------------

    private void validate(BulkRuleRequest req) {
        if (req == null || req.assignments() == null || req.assignments().isEmpty()) {
            throw badRequest("assignments must not be empty");
        }

        int totalTargets = 0;
        for (RuleAssignmentDto assignment : req.assignments()) {
            if (assignment == null) {
                throw badRequest("assignment must not be null");
            }
            validateRule(assignment.rule());

            List<TargetDto> targets = assignment.targets();
            if (targets == null || targets.isEmpty()) {
                throw badRequest("each assignment must have at least one target");
            }
            totalTargets += targets.size();
            for (TargetDto target : targets) {
                requireTarget(target);
                validateTrackedTicker(target);
            }
        }

        if (totalTargets > maxTargetsPerRequest) {
            throw badRequest("too many targets: " + totalTargets
                    + " (max " + maxTargetsPerRequest + ")");
        }
    }

    private void validateRule(RuleDto rule) {
        if (rule == null || rule.tiers() == null || rule.tiers().isEmpty()) {
            throw badRequest("tiers must not be empty");
        }
        List<TierDto> tiers = rule.tiers();

        boolean[] seen = new boolean[5]; // indices 1..4
        int maxTier = 0;
        for (TierDto tier : tiers) {
            int t = tier.tier();
            if (t < 1 || t > 4) {
                throw badRequest("tier must be in [1, 4]: got " + t);
            }
            if (seen[t]) {
                throw badRequest("duplicate tier: " + t);
            }
            seen[t] = true;
            maxTier = Math.max(maxTier, t);

            if (tier.minNotional() < 0) {
                throw badRequest("minNotional must be >= 0: got " + tier.minNotional());
            }
            if (tier.maxDistance() <= 0 || tier.maxDistance() > maxDistanceUpperBound) {
                throw badRequest("maxDistance must be in (0, " + maxDistanceUpperBound
                        + "]: got " + tier.maxDistance());
            }
        }

        // Full tier-list validation: the provided tiers must form a contiguous set starting at 1.
        // {1,2,4} is rejected because tier 3 is missing.
        if (maxTier != tiers.size()) {
            throw badRequest("tiers must be contiguous starting at 1 (no gaps); "
                    + "got " + tiers.size() + " tiers with max tier " + maxTier);
        }
    }

    private void requireTarget(TargetDto target) {
        if (target == null || target.symbol() == null || target.symbol().isBlank()
                || target.market() == null) {
            throw badRequest("each target requires a symbol and market");
        }
    }

    private void validateTrackedTicker(TargetDto target) {
        String symbol = normalizeSymbol(target.symbol());
        Ticker ticker = tickerRegistry.find(symbol)
                .orElseThrow(() -> badRequest("unknown symbol: " + symbol));

        boolean covered = switch (target.market()) {
            case SPOT -> ticker.hasSpot();
            case FUTURES -> ticker.hasFutures();
        };
        if (!covered) {
            throw badRequest(symbol + " is not tracked on market " + target.market());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static List<TierDto> toTierDtos(List<ClassificationRuleEntity> rows) {
        List<TierDto> tiers = new ArrayList<>(rows.size());
        for (ClassificationRuleEntity row : rows) {
            tiers.add(new TierDto(row.getTierNo(), row.getMinNotional(), row.getMaxDistance()));
        }
        return tiers;
    }

    private static String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase();
    }

    private static ApiException badRequest(String message) {
        return ApiException.badRequest(message);
    }
}
