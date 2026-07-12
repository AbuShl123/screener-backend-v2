package dev.abu.screener_backend.payment.multicard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /payment/{uuid}} response envelope ({@code {success, data: PaymentModel}}). Only the
 * fields the reconciliation sweep needs are mapped; {@code status} ∈
 * {@code draft|progress|billing|success|error|revert}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MulticardPaymentResponse(Boolean success, Data data, MulticardError error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            String uuid,
            String status,
            String ps,
            @JsonProperty("total_amount") Long totalAmount,
            @JsonProperty("receipt_url") String receiptUrl,
            @JsonProperty("ps_response_msg") String psResponseMsg
    ) {}
}
