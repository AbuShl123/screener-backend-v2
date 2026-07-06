package dev.abu.screener_backend.auth.dto;

/**
 * Register no longer returns a token pair — the account exists but is not yet usable. The SPA shows a
 * "check your inbox" screen keyed off {@code status = "VERIFICATION_REQUIRED"} and offers a resend
 * button using {@code email}.
 */
public record RegisterResponse(String status, String email) {}
