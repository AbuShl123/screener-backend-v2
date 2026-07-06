package dev.abu.screener_backend.payment.multicard;

import dev.abu.screener_backend.payment.multicard.dto.MulticardCallbackPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the MD5 success-callback signature. Uses a deterministic vector computed the same way
 * Multicard documents — MD5 of {@code {store_id}{invoice_id}{amount}{secret}} — then verifies tampering
 * any field invalidates the signature.
 */
class MulticardSignatureTest {

    private static final String SECRET = "Pw18axeBFo8V7NamKHXX";
    private static final String STORE_ID = "6";
    private static final String INVOICE_ID = "2024864028760";
    private static final long AMOUNT = 20000L;

    private static MulticardCallbackPayload payload(String sign) {
        return new MulticardCallbackPayload(STORE_ID, AMOUNT, INVOICE_ID, "bill", "2024-12-14 14:36:31",
                "998930601725", "860030******5959", "uzcard", "tok", "uuid-1", "https://r", sign);
    }

    @Test
    void validSignatureAccepted() {
        String sign = MulticardSignature.md5Hex(STORE_ID + INVOICE_ID + AMOUNT + SECRET);
        assertTrue(MulticardSignature.valid(payload(sign), SECRET));
    }

    @Test
    void tamperedAmountRejected() {
        // Signature computed for AMOUNT, but the payload carries a different amount.
        String sign = MulticardSignature.md5Hex(STORE_ID + INVOICE_ID + AMOUNT + SECRET);
        MulticardCallbackPayload tampered = new MulticardCallbackPayload(STORE_ID, 99999L, INVOICE_ID, "bill",
                "2024-12-14 14:36:31", "998930601725", "860030******5959", "uzcard", "tok", "uuid-1", "https://r", sign);
        assertFalse(MulticardSignature.valid(tampered, SECRET));
    }

    @Test
    void wrongSecretRejected() {
        String sign = MulticardSignature.md5Hex(STORE_ID + INVOICE_ID + AMOUNT + SECRET);
        assertFalse(MulticardSignature.valid(payload(sign), "not-the-secret"));
    }

    @Test
    void missingSignRejected() {
        assertFalse(MulticardSignature.valid(payload(null), SECRET));
    }
}
