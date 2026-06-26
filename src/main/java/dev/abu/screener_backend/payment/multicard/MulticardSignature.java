package dev.abu.screener_backend.payment.multicard;

import dev.abu.screener_backend.payment.multicard.dto.MulticardCallbackPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Verifies the Multicard success-callback signature.
 *
 * <p>The default success callback signs with <strong>MD5</strong> of
 * {@code {store_id}{invoice_id}{amount}{secret}} (concatenated, no separators), over the values in
 * the callback payload itself. The comparison against the provider's {@code sign} is constant-time.
 *
 * <p>The {@link #sha1Hex} variant is stubbed for a future switch to full per-status webhooks, which
 * sign with SHA-1 of {@code {uuid}{invoice_id}{amount}{secret}} — isolating that here keeps the switch
 * a localized change.
 */
public final class MulticardSignature {

    private MulticardSignature() {}

    /** True when the payload's {@code sign} matches MD5({store_id}{invoice_id}{amount}{secret}). */
    public static boolean valid(MulticardCallbackPayload payload, String secret) {
        if (payload == null || payload.sign() == null) {
            return false;
        }
        String expected = md5Hex(payload.storeId() + payload.invoiceId() + payload.amount() + secret);
        return constantTimeEquals(expected, payload.sign());
    }

    /** MD5 of the success-callback string {store_id}{invoice_id}{amount}{secret}. */
    public static String md5Hex(String input) {
        return hex("MD5", input);
    }

    /** SHA-1 of the full-webhook string {uuid}{invoice_id}{amount}{secret} — reserved for a future switch. */
    public static String sha1Hex(String input) {
        return hex("SHA-1", input);
    }

    private static String hex(String algorithm, String input) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available", e);
        }
    }

    /** Case-insensitive hex comparison that does not short-circuit on the first mismatch. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= Character.toLowerCase(a.charAt(i)) ^ Character.toLowerCase(b.charAt(i));
        }
        return diff == 0;
    }
}
