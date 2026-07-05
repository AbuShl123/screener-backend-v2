package dev.abu.screener_backend.email;

import dev.abu.screener_backend.config.EmailProperties;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit unit test (no Mockito, no Spring context — same house style as
 * {@code EntitlementServiceTest}). {@link CapturingMailSender} subclasses the real
 * {@link JavaMailSenderImpl} to stash the sent {@link MimeMessage} offline (its
 * {@code createMimeMessage()} works without a connection); a standalone {@link SpringTemplateEngine}
 * backed by a {@link ClassLoaderTemplateResolver} loads the real production template from the
 * classpath, proving it actually resolves and renders.
 */
class EmailServiceTest {

    private static class CapturingMailSender extends JavaMailSenderImpl {
        private MimeMessage captured;

        @Override
        public void send(MimeMessage mimeMessage) throws MailException {
            try {
                // Mirrors JavaMailSenderImpl.doSend(), which normally calls this right before the
                // real Transport hands off the message — without it, body-part Content-Type headers
                // are never finalized and default to "text/plain" regardless of actual content.
                mimeMessage.saveChanges();
            } catch (MessagingException e) {
                throw new MailParseException(e);
            }
            this.captured = mimeMessage;
        }
    }

    private CapturingMailSender mailSender;
    private EmailService service;

    @BeforeEach
    void setUp() {
        mailSender = new CapturingMailSender();
        EmailProperties props = new EmailProperties(
                "noreply@tc-screener.com",
                "TC Screener",
                Duration.ofHours(24),
                Duration.ofMinutes(1),
                "https://app.tc-screener.com/verify-email"
        );
        service = new EmailService(mailSender, props, templateEngine());
    }

    private static SpringTemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /** Recursively walks a (possibly nested) multipart tree for the first body part of the given mime type. */
    private static String findPart(MimeMultipart multipart, String mimeType) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.isMimeType(mimeType)) {
                return (String) part.getContent();
            }
            Object content = part.getContent();
            if (content instanceof MimeMultipart nested) {
                String found = findPart(nested, mimeType);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void sendsMultipartMessageWithRecipientSubjectAndFrom() throws Exception {
        service.sendVerificationEmail("user@example.com", "Alice", "raw-token-123");

        MimeMessage mime = mailSender.captured;
        assertNotNull(mime);
        assertEquals("user@example.com", ((InternetAddress) mime.getAllRecipients()[0]).getAddress());
        assertEquals("Confirm your TC Screener email", mime.getSubject());

        InternetAddress from = (InternetAddress) mime.getFrom()[0];
        assertEquals("noreply@tc-screener.com", from.getAddress());
        assertEquals("TC Screener", from.getPersonal());
    }

    @Test
    void sendsBothPlainTextAndHtmlParts() throws Exception {
        service.sendVerificationEmail("user@example.com", "Alice", "raw-token-123");

        MimeMultipart multipart = (MimeMultipart) mailSender.captured.getContent();
        String plain = findPart(multipart, "text/plain");
        String html = findPart(multipart, "text/html");

        assertNotNull(plain);
        assertNotNull(html);

        String expectedLink = "https://app.tc-screener.com/verify-email?token=raw-token-123";
        assertTrue(plain.contains(expectedLink));
        assertTrue(plain.contains("Hi Alice,"));
        assertTrue(html.contains(expectedLink));
        assertTrue(html.contains("Hi Alice,"));
    }

    @Test
    void blankFirstNameRendersGenericGreeting() throws Exception {
        service.sendVerificationEmail("user@example.com", "  ", "raw-token-123");

        MimeMultipart multipart = (MimeMultipart) mailSender.captured.getContent();
        String html = findPart(multipart, "text/html");
        String plain = findPart(multipart, "text/plain");

        assertTrue(html.contains("Hi there,"));
        assertTrue(plain.contains("Hi there,"));
    }

    @Test
    void firstNameIsHtmlEscapedInHtmlPartButNotInPlainText() throws Exception {
        service.sendVerificationEmail("user@example.com", "<b>x</b>", "raw-token-123");

        MimeMultipart multipart = (MimeMultipart) mailSender.captured.getContent();
        String html = findPart(multipart, "text/html");
        String plain = findPart(multipart, "text/plain");

        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;"));
        assertFalse(html.contains("<b>x</b>"));
        // Plain text is not HTML — the raw name is expected verbatim, no escaping applies.
        assertTrue(plain.contains("<b>x</b>"));
    }
}
