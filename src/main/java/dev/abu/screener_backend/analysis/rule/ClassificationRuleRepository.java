package dev.abu.screener_backend.analysis.rule;

import dev.abu.screener_backend.binance.websocket.Market;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClassificationRuleRepository
        extends JpaRepository<ClassificationRuleEntity, UUID> {

    List<ClassificationRuleEntity> findByUserId(UUID userId);

    List<ClassificationRuleEntity> findByUserIdAndSymbolAndMarket(
            UUID userId, String symbol, Market market);

    /**
     * Bulk DELETE issued as a single immediate SQL statement via JPQL.
     *
     * <p>This must NOT be a derived {@code deleteBy…} method: those do a retrieve-then-{@code remove}
     * that only queues the deletes in the persistence context. At flush time Hibernate orders
     * inserts before deletes, so the replacement inserts in {@code upsertRules} would collide with
     * the not-yet-deleted old rows on {@code uq_rule_tier}. A {@code @Modifying @Query} bulk delete
     * runs immediately, guaranteeing the old tier rows are gone before the replacement inserts.
     */
    @Modifying
    @Query("delete from ClassificationRuleEntity r "
            + "where r.user.id = :userId and r.symbol = :symbol and r.market = :market")
    void deleteByUserIdAndSymbolAndMarket(@Param("userId") UUID userId,
                                          @Param("symbol") String symbol,
                                          @Param("market") Market market);
}
