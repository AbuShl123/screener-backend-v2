package dev.abu.screener_backend.payment.multicard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /payment/invoice} response envelope. On success, {@code data} carries the transaction
 * {@code uuid} (persist!) and the {@code checkout_url}; on failure, {@code error} is populated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MulticardInvoiceResponse(Boolean success, Data data, MulticardError error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            String uuid,
            @JsonProperty("checkout_url") String checkoutUrl
    ) {}
}
