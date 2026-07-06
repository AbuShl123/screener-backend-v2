package dev.abu.screener_backend.payment.multicard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code POST /auth} response. {@code expiry} is a {@code "yyyy-MM-dd HH:mm:ss"} string in GMT+5;
 * the client does not parse it — it caches the token with its own conservative TTL and refetches on a
 * {@code 401}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MulticardAuthResponse(String token, String role, String expiry) {}
