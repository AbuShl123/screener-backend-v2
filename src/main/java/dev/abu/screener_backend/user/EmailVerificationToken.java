package dev.abu.screener_backend.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A single-use, opaque email-verification token. Structural mirror of {@link RefreshToken}: only the
 * SHA-256 hash is stored, never the raw value handed to the user in the link. One active token per
 * user (delete-then-insert on register/resend); single-use is enforced by deleting the row on
 * successful verification. {@code createdAt} doubles as the resend-cooldown clock.
 */
@Entity
@Table(name = "email_verification_tokens")
@Getter
@Setter
@NoArgsConstructor
public class EmailVerificationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void prePersist() {
        createdAt = Instant.now();
    }
}
