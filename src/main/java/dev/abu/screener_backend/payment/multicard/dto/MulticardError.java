package dev.abu.screener_backend.payment.multicard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The {@code error{code, details}} half of the Multicard response envelope. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MulticardError(String code, String details) {}
