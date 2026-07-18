package dev.abu.screener_backend.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Bumped on every successful, entitled WebSocket open. NULL = never seen under usage tracking. */
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @PrePersist
    private void prePersist() {
        createdAt = Instant.now();
        if (role == null) role = UserRole.USER;
        enabled = true;
        // Every freshly registered user starts unverified; grandfathered rows carry TRUE from V12 and
        // never run @PrePersist.
        emailVerified = false;
    }
}
