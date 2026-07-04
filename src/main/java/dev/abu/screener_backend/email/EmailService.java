package dev.abu.screener_backend.email;

import dev.abu.screener_backend.config.EmailProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * The single seam over Spring's {@link JavaMailSender} (SMTP). Thin by design: it composes the
 * message and delegates. This is the one class we would reimplement if we ever moved off SMTP to an
 * HTTP transactional-API sender — no vendor-agnostic port is introduced until then (SMTP is universal
 * on the receiving end and {@code JavaMailSender} is itself the swappable interface).
 *
 * <p>Send failures propagate to the async listener, which logs them WARN; the user recovers via
 * resend.
 */
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final EmailProperties props;

    public EmailService(JavaMailSender mailSender, EmailProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    /**
     * Sends the verification email carrying the link to the SPA verification page
     * {@code ${verify-page-url}?token=<raw>}. Loading that page consumes nothing; the user's Confirm
     * click is what POSTs the token to {@code /api/auth/verify-email}. The raw token is already
     * Base64URL (URL-safe), so no encoding is applied.
     */
    public void sendVerificationEmail(String toEmail, String firstName, String rawToken) {
        String link = props.verifyPageUrl() + "?token=" + rawToken;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(props.fromName() + " <" + props.fromAddress() + ">");
        message.setTo(toEmail);
        message.setSubject("Confirm your TC Screener email");
        message.setText(
                "Hi " + (firstName == null || firstName.isBlank() ? "there" : firstName) + ",\n\n"
                        + "Thanks for registering with TC Screener. Please confirm your email address by "
                        + "opening the link below:\n\n"
                        + link + "\n\n"
                        + "This link expires in 24 hours. If it expires, request a new one from the app.\n\n"
                        + "If you did not create this account, you can ignore this email.\n\n"
                        + "— TC Screener");
        mailSender.send(message);
    }
}
