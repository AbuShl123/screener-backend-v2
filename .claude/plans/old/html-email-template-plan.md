# HTML Email Template — Implementation Plan

## Scope

Upgrade `EmailService.sendVerificationEmail(...)` from a plain-text `SimpleMailMessage` to a
**multipart/alternative** message carrying (a) the existing plain-text copy as a fallback and (b) an
HTML body rendered from a **Thymeleaf** template with dynamically injected variables (`firstName`,
`confirmUrl`).

The public method signature stays identical — `sendVerificationEmail(String toEmail, String
firstName, String rawToken)` — so `RegistrationEmailEvent`, `RegistrationEmailListener`,
`AuthService`, and `AuthController` are **not touched**. This is an isolated change to the one seam
described in the email-verification plan (§8: "upgrade to a `MimeMessage` + minimal HTML later").

**Not in scope**: password-reset / receipt / subscription emails (they will *reuse* the shared
layout established here — see "Future" below); i18n of copy; a non-SMTP sender.

---

## Locked decisions (from discussion)

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| 1 | Templating engine | **Thymeleaf** (`spring-boot-starter-thymeleaf`) | Spring-standard for HTML mail. **Auto HTML-escaping** of `firstName` (untrusted input → HTML) is the default, not something to remember. Fragments will let the coming transactional emails share this chrome. Email is async/off-thread/low-frequency, so the codebase's allocation ethos doesn't apply — maintainability + safety win. |
| 2 | HTML-only vs. text+HTML | **multipart/alternative** (keep plain text) | Spam filters penalize HTML-only mail (deliverability is already a rollout concern — SPF/DKIM); text-only/accessibility clients need the fallback. Reuse today's copy verbatim as the text part. |
| 3 | Message type | `MimeMessage` via `MimeMessageHelper` | `SimpleMailMessage` is plain-text only — it physically cannot carry HTML. |

---

## 1. Dependency (`pom.xml`)

Add next to `spring-boot-starter-mail` (~line 56). Version managed by the Boot BOM — no explicit
version, same as the other starters:

```xml
<!-- Thymeleaf — HTML email templating (auto-escaping + shared fragments for transactional mail).
     Autoconfigures a SpringTemplateEngine resolving classpath:/templates/. -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
```

No `application.yml` Thymeleaf config needed — the autoconfigured resolver already points at
`classpath:/templates/` with `spring.thymeleaf.cache=true` in prod (template parsed once, then
cached; ideal for a fixed email template).

---

## 2. Template file

Place the designer's HTML at:

```
src/main/resources/templates/email/confirm-email.html
```

(Resolved as logical name `email/confirm-email` — no `.html` suffix in code.)

Convert the two mustache placeholders to Thymeleaf. **Only these two lines change**; the rest of the
120-line template is copied verbatim (Thymeleaf's default HTML mode is lenient — it tolerates the
`<meta>` void tags, the `<!--[if mso]>` MSO conditional comments, `&nbsp;`, and the `xmlns` on
`<html>` without complaint).

| Current (mustache) | Thymeleaf |
|---|---|
| `<p ...>Hi {{firstName}},</p>` | `<p ... th:text="'Hi ' + ${firstName} + ','">Hi there,</p>` |
| `href="{{confirmUrl}}"` (button, line 75) | `th:href="${confirmUrl}"` |
| `href="{{confirmUrl}}"` + `>{{confirmUrl}}<` (fallback link, line 87) | `th:href="${confirmUrl}" th:text="${confirmUrl}"` |

The literal fallback text (`Hi there,`, the raw button URL) stays as static preview content —
Thymeleaf replaces it at render time, and it makes the file previewable in a browser.

> **Escaping note**: `th:text` HTML-escapes automatically, so a `firstName` of `<b>x</b>` renders as
> literal text, not markup. `th:href` URL-context-escapes. Do **not** switch these to `th:utext`
> (unescaped) — that would reintroduce the injection hole.

**Optional nicety** (recommended, cheap): parameterize the hard-coded "24 hours" (lines 80) so it
tracks `verification-token-expiry`. Add an `expiryHours` variable
(`th:text="${expiryHours} + ' hours'"`). If we skip it, "24 hours" stays a literal and must be kept
in sync with config by hand.

---

## 3. `EmailService` rewrite

Replace the `SimpleMailMessage` body with a `MimeMessage`. Inject the autoconfigured Thymeleaf
engine.

```java
private final JavaMailSender mailSender;
private final EmailProperties props;
private final SpringTemplateEngine templateEngine;   // org.thymeleaf.spring6.SpringTemplateEngine
                                                     // (autoconfigured bean; ITemplateEngine also works)
```

New `sendVerificationEmail`:

```java
public void sendVerificationEmail(String toEmail, String firstName, String rawToken) {
    String link = props.verifyPageUrl() + "?token=" + rawToken;
    String displayName = (firstName == null || firstName.isBlank()) ? "there" : firstName;

    Context ctx = new Context();                       // org.thymeleaf.context.Context
    ctx.setVariable("firstName", displayName);
    ctx.setVariable("confirmUrl", link);
    ctx.setVariable("expiryHours", props.verificationTokenExpiry().toHours());  // if §2 nicety taken
    String html = templateEngine.process("email/confirm-email", ctx);

    String plainText = buildPlainText(displayName, link);   // extracted from today's setText(...) copy

    try {
        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");   // true = multipart
        helper.setFrom(props.fromAddress(), props.fromName());   // encodes display name for us
        helper.setTo(toEmail);
        helper.setSubject("Confirm your TC Screener email");
        helper.setText(plainText, html);   // (plain, html) → multipart/alternative, HTML preferred
        mailSender.send(mime);
    } catch (MessagingException | UnsupportedEncodingException e) {
        // Wrap the checked mail exceptions; the async listener catches and logs WARN, user resends.
        throw new IllegalStateException("Failed to build/send verification email to " + toEmail, e);
    }
}
```

- `buildPlainText(displayName, link)` — a private method holding the current copy verbatim (the
  "Hi … / Thanks for registering / <link> / expires in 24h / ignore if not you / — TC Screener"
  block). Zero copy change; it just moves into a helper so both branches read cleanly.
- `MimeMessageHelper.setText(plain, html)` orders the parts correctly (text first, HTML second) per
  the MIME spec so conformant clients show the HTML and fallback clients show the text.
- Failure still propagates to `RegistrationEmailListener`, which already `catch (Exception)` → WARN
  → user can resend. No listener change.

Imports added: `jakarta.mail.MessagingException`, `jakarta.mail.internet.MimeMessage`,
`org.springframework.mail.javamail.MimeMessageHelper`, `org.thymeleaf.context.Context`,
`org.thymeleaf.spring6.SpringTemplateEngine`, `java.io.UnsupportedEncodingException`. Remove the
`SimpleMailMessage` import.

Update the class Javadoc: it currently says "composes the message and delegates" — extend to note it
renders an HTML body via Thymeleaf and sends multipart/alternative (text + HTML).

---

## 4. Config

**No new keys required.** `spring.mail.*`, `screener.email.from-*`, and `verify-page-url` already
exist and are consumed as-is. `verification-token-expiry` (already present) is reused for the
`expiryHours` template variable if the §2 nicety is taken. `EmailProperties` is unchanged and stays
registered in `WebClientConfig`'s `@EnableConfigurationProperties`.

---

## 5. Tests (`EmailServiceTest`)

Follow the house style — plain JUnit, no Mockito, no Spring context (as in `EntitlementServiceTest`
/ `ClassificationRuleServiceTest`).

- **Capture the sent message without SMTP**: subclass `JavaMailSenderImpl` and override
  `send(MimeMessage)` to stash the message in a field. `createMimeMessage()` on the real impl works
  offline (no connection needed).
- **Build a standalone Thymeleaf engine** for the test: `SpringTemplateEngine` +
  `ClassLoaderTemplateResolver` (prefix `templates/email/`, suffix `.html`, mode `HTML`) so it loads
  the real production template from the classpath. This proves the template actually resolves and
  renders.
- Assertions on the captured `MimeMessage` (read via `MimeMessageParser` or by walking the
  `MimeMultipart`):
  1. Recipient = `toEmail`, subject set, `From` carries the configured name+address.
  2. Two body parts exist: one `text/plain`, one `text/html` (multipart/alternative).
  3. HTML part contains the resolved `confirmUrl` (`verifyPageUrl + "?token=" + rawToken`) and the
     `firstName`.
  4. Plain-text part contains the raw link and the greeting.
  5. **Escaping regression**: with `firstName = "<b>x</b>"`, the HTML part contains `&lt;b&gt;x&lt;/b&gt;`
     and **not** `<b>x</b>` — guards against a future `th:utext` slip.
  6. Blank/null `firstName` → greeting renders "Hi there,".

---

## 6. Docs & memory

- Update `CURRENT_STATE.md` email section: EmailService now sends multipart/alternative with a
  Thymeleaf-rendered HTML body (`templates/email/confirm-email.html`); note `spring-boot-starter-thymeleaf`
  added and that it's the shared base for future transactional emails.
- Add a one-line "as-built" note to `.claude/plans/email-verification-plan.md` §8 / §14 (the "upgrade
  to MimeMessage + HTML later" item is now done).

---

## 7. Deliverables checklist

- [ ] `pom.xml`: `spring-boot-starter-thymeleaf`
- [ ] `src/main/resources/templates/email/confirm-email.html` (template + 2 placeholder conversions
      [+ optional `expiryHours`])
- [ ] `EmailService`: inject `SpringTemplateEngine`; render HTML; `MimeMessageHelper` multipart
      (plain + HTML); `buildPlainText` helper; updated Javadoc
- [ ] `EmailServiceTest`: capture-subclass + standalone engine; recipient/subject/from, two parts,
      link+name present, escaping regression, blank-name greeting
- [ ] `CURRENT_STATE.md` + email-verification-plan.md as-built note

---

## 8. Verification

1. `mvn -q test` — `EmailServiceTest` green.
2. Local smoke: point `MAIL_*` at a catcher (Mailhog/Mailtrap or a real inbox), register a fresh
   account, confirm the received mail renders the card in an HTML client (Gmail/Outlook web) and
   degrades to the text copy in a text-only view. Verify the Confirm button and the fallback link
   both carry `?token=<raw>` and land on the SPA verify page.

---

## 9. Future (enabled by this change)

- **Shared layout fragment**: when password-reset / receipt / subscription emails land, extract the
  header + card shell + footer into `templates/email/layout.html` and have each email
  `th:replace`/`th:insert` it — design the chrome once. This plan deliberately keeps `confirm-email.html`
  self-contained for now (no premature fragment extraction with only one email).
- **i18n**: Thymeleaf `#{...}` message resolution + per-locale `.properties` when ru/uz/en copy is
  needed.
