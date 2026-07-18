package dev.abu.screener_backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /** Bulk update — bumps last_seen_at on the connect path without loading the User entity. */
    @Modifying
    @Query("update User u set u.lastSeenAt = :ts where u.id = :id")
    void updateLastSeenAt(@Param("id") UUID id, @Param("ts") Instant ts);
}
