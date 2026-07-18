package dev.abu.screener_backend.monitoring;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of one successful, entitled WebSocket connect. One row per open (no dedup, no
 * bucketing at write time — all aggregation is read-time in {@link ConnectionUsageService}).
 *
 * <p>{@code userId} is a plain column, <b>not</b> a {@code @ManyToOne} — the write path never needs to
 * load the {@link dev.abu.screener_backend.user.User}. The PK is a compact {@code BIGINT} identity
 * because this is a high-volume log, unlike the UUID-keyed low-volume entities elsewhere.
 */
@Entity
@Table(name = "connection_events")
@Getter
@NoArgsConstructor
public class ConnectionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    public ConnectionEvent(UUID userId, Instant connectedAt) {
        this.userId = userId;
        this.connectedAt = connectedAt;
    }
}
