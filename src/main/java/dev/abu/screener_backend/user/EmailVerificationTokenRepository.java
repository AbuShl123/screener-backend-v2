package dev.abu.screener_backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /** Delete-then-insert on register/resend to keep one active token per user. */
    @Modifying
    @Query("DELETE FROM EmailVerificationToken t WHERE t.user.id = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    /** The user's most recent token — drives the resend cooldown check. */
    Optional<EmailVerificationToken> findFirstByUser_IdOrderByCreatedAtDesc(UUID userId);
}
