package dev.abu.screener_backend.payment.multicard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The success-callback body Multicard POSTs to our {@code callback_url}. There is no {@code status}
 * field (the default callback fires only on success). {@code amount} is in <strong>tiyin</strong>.
 *
 * <p>{@code sign} is the MD5 of {@code {store_id}{invoice_id}{amount}{secret}} — verified by
 * {@code MulticardSignature} against the values in <em>this</em> payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MulticardCallbackPayload(
        @JsonProperty("store_id") String storeId,
        Long amount,
        @JsonProperty("invoice_id") String invoiceId,
        @JsonProperty("billing_id") String billingId,
        @JsonProperty("payment_time") String paymentTime,
        String phone,
        @JsonProperty("card_pan") String cardPan,
        String ps,
        @JsonProperty("card_token") String cardToken,
        String uuid,
        @JsonProperty("receipt_url") String receiptUrl,
        String sign
) {}
