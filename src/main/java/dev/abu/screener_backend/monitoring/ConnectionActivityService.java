package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Write path for persisted usage analytics. Called from the WebSocket {@code @OnOpen} once a
 * connection is fully established and entitled — far from the market-data hot path, so ordinary JPA.
 */
@Service
@RequiredArgsConstructor
public class ConnectionActivityService {

    private final UserRepository userRepository;
    private final ConnectionEventRepository connectionEventRepository;

    /**
     * Records one successful connect: bumps {@code users.last_seen_at} and appends a
     * {@code connection_events} row, both in one transaction with the same instant (so a user's
     * {@code last_seen_at} equals their latest event's {@code connected_at}).
     */
    @Transactional
    public void recordConnection(UUID userId) {
        Instant now = Instant.now();
        userRepository.updateLastSeenAt(userId, now);      // bulk UPDATE, no entity load
        connectionEventRepository.save(new ConnectionEvent(userId, now));
    }
}
