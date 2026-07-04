package dev.abu.screener_backend.email;

import dev.abu.screener_backend.config.EmailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link EmailService}: the verification link is composed from {@code verify-page-url} +
 * the raw token and addressed to the recipient. {@link JavaMailSender} is a reflective proxy capturing
 * the sent {@link SimpleMailMessage} (no Mockito, matching the codebase house style).
 */
class EmailServiceTest {

    private final EmailProperties props = new EmailProperties(
            "noreply@tc-screener.com", "TC Screener", Duration.ofHours(24), Duration.ofMinutes(1),
            "https://app.tc-screener.com/verify-email");

    @Test
    void sendVerificationEmailComposesLinkAndRecipient() {
        AtomicReference<SimpleMailMessage> captured = new AtomicReference<>();
        EmailService service = new EmailService(mailSender(captured), props);

        service.sendVerificationEmail("user@example.com", "Alice", "RAW-TOKEN-123");

        SimpleMailMessage msg = captured.get();
        assertNotNull(msg, "a message was sent");
        assertNotNull(msg.getTo());
        assertEquals("user@example.com", msg.getTo()[0]);
        assertTrue(msg.getFrom() != null && msg.getFrom().contains("noreply@tc-screener.com"),
                "From carries the configured sender address");
        assertTrue(msg.getText().contains(
                        "https://app.tc-screener.com/verify-email?token=RAW-TOKEN-123"),
                "body carries the link to the SPA verify page + raw token");
    }

    private JavaMailSender mailSender(AtomicReference<SimpleMailMessage> captured) {
        return (JavaMailSender) Proxy.newProxyInstance(
                JavaMailSender.class.getClassLoader(),
                new Class<?>[]{JavaMailSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("send") && args.length == 1 && args[0] instanceof SimpleMailMessage m) {
                        captured.set(m);
                    }
                    return null;
                });
    }
}
