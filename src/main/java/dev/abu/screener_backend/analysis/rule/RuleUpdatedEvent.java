package dev.abu.screener_backend.analysis.rule;

import java.util.UUID;

/**
 * Published by {@link ClassificationRuleService} after a successful rule write (upsert or delete),
 * still inside the {@code @Transactional} boundary. {@code UserFeedRegistry} listens with
 * {@code @TransactionalEventListener(AFTER_COMMIT)}, so the listener's DB re-read always sees the
 * committed data. A plain record — Spring handles arbitrary event objects since 4.2.
 */
public record RuleUpdatedEvent(UUID userId) {}
