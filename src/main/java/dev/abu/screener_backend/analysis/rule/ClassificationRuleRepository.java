package dev.abu.screener_backend.analysis.rule;

import dev.abu.screener_backend.binance.websocket.Market;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

public interface ClassificationRuleRepository
        extends JpaRepository<ClassificationRuleEntity, UUID> {

    List<ClassificationRuleEntity> findByUserId(UUID userId);

    List<ClassificationRuleEntity> findByUserIdAndSymbolAndMarket(
            UUID userId, String symbol, Market market);

    /**
     * Bulk DELETE issued as a single SQL statement (not entity-by-entity). Because it executes
     * immediately rather than at flush time, the delete is guaranteed to run before the
     * replacement inserts in {@code upsertRules}, avoiding a {@code uq_rule_tier} violation from
     * Hibernate reordering insert-before-delete within one transaction.
     */
    @Modifying
    void deleteByUserIdAndSymbolAndMarket(UUID userId, String symbol, Market market);
}
