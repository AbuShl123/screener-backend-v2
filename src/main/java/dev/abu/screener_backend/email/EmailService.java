package dev.abu.screener_backend.email;

import dev.abu.screener_backend.config.EmailProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.UnsupportedEncodingException;

/**
 * The single seam over Spring's {@link JavaMailSender} (SMTP). Thin by design: it composes the
 * message and delegates. This is the one class we would reimplement if we ever moved off SMTP to an
 * HTTP transactional-API sender — no vendor-agnostic port is introduced until then (SMTP is universal
 * on the receiving end and {@code JavaMailSender} is itself the swappable interface).
 *
 * <p>Sends multipart/alternative: an HTML body rendered from a Thymeleaf template plus a plain-text
 * fallback for spam filters / accessibility clients.
 *
 * <p>Send failures propagate to the async listener, which logs them WARN; the user recovers via
 * resend.
 */
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final EmailProperties props;
    private final SpringTemplateEngine templateEngine;

    public EmailService(JavaMailSender mailSender, EmailProperties props, SpringTemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.props = props;
        this.templateEngine = templateEngine;
    }

    /**
     * Sends the verification email carrying the link to the SPA verification page
     * {@code ${verify-page-url}?token=<raw>}. Loading that page consumes nothing; the user's Confirm
     * click is what POSTs the token to {@code /api/auth/verify-email}. The raw token is already
     * Base64URL (URL-safe), so no encoding is applied.
     */
    public void sendVerificationEmail(String toEmail, String firstName, String rawToken) {
        String link = props.verifyPageUrl() + "?token=" + rawToken;
        String displayName = (firstName == null || firstName.isBlank()) ? "there" : firstName;

        Context ctx = new Context();
        ctx.setVariable("firstName", displayName);
        ctx.setVariable("confirmUrl", link);
        ctx.setVariable("expiryHours", props.verificationTokenExpiry().toHours());
        String html = templateEngine.process("email/confirm-email", ctx);

        String plainText = buildPlainText(displayName, link);

        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(props.fromAddress(), props.fromName());
            helper.setTo(toEmail);
            helper.setSubject("Confirm your TC Screener email");
            helper.setText(plainText, html);
            mailSender.send(mime);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException("Failed to build/send verification email to " + toEmail, e);
        }
    }

    private String buildPlainText(String displayName, String link) {
        return "Hi " + displayName + ",\n\n"
                + "Thanks for registering with TC Screener. Please confirm your email address by "
                + "opening the link below:\n\n"
                + link + "\n\n"
                + "This link expires in 24 hours. If it expires, request a new one from the app.\n\n"
                + "If you did not create this account, you can ignore this email.\n\n"
                + "— TC Screener";
    }
}
