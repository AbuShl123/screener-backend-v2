package dev.abu.screener_backend.auth.dto;

/**
 * The raw token the SPA read from the verification-page URL ({@code ?token=<raw>}) and submits when
 * the user clicks Confirm.
 */
public record VerifyEmailRequest(String token) {}
