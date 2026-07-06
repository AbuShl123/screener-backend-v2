package dev.abu.screener_backend.email;

import dev.abu.screener_backend.auth.RegistrationEmailEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the verification email off the request thread. {@code AFTER_COMMIT} guarantees the token row
 * is committed before the link is mailed (no send for a row that later rolls back); {@code @Async}
 * (on the dedicated {@code emailExecutor}) keeps the blocking SMTP round-trip off Tomcat. A send
 * failure is logged WARN and is non-fatal — the user can trigger a resend.
 */
@Component
public class RegistrationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEmailListener.class);

    private final EmailService emailService;

    public RegistrationEmailListener(EmailService emailService) {
        this.emailService = emailService;
    }

    @Async("emailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistrationEmail(RegistrationEmailEvent event) {
        try {
            emailService.sendVerificationEmail(event.email(), event.firstName(), event.rawToken());
        } catch (Exception e) {
            log.warn("Failed to send verification email to {}: {}", event.email(), e.getMessage());
        }
    }
}
