package dev.abu.screener_backend.auth.dto;

/**
 * Deliberately generic — returned for every resend request regardless of whether the email exists, is
 * already verified, or is on cooldown, so the endpoint leaks no account-enumeration or cooldown oracle.
 */
public record ResendVerificationResponse(String message) {}
