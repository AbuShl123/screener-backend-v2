package dev.abu.screener_backend.payment.multicard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /payment/invoice} request body. {@code amount} is in <strong>tiyin</strong> (converted
 * at the adapter boundary). {@code ofd} (fiscalization) is attached only when enabled — {@code null}
 * fields are omitted from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MulticardInvoiceRequest(
        @JsonProperty("store_id") String storeId,
        long amount,
        @JsonProperty("invoice_id") String invoiceId,
        @JsonProperty("callback_url") String callbackUrl,
        @JsonProperty("return_url") String returnUrl,
        @JsonProperty("return_error_url") String returnErrorUrl,
        Integer ttl,
        String lang,
        Object ofd
) {}
