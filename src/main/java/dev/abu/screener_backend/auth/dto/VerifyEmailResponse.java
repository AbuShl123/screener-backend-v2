package dev.abu.screener_backend.auth.dto;

/**
 * Discriminated verification outcome the SPA branches on: {@code "success"}, {@code "expired"}, or
 * {@code "invalid"}. Returned with HTTP 200 for every case — an expired/invalid link is an expected
 * user-facing outcome the SPA renders (with a resend affordance), not a server error, so it carries a
 * structured status rather than being thrown as an {@code ApiException}.
 */
public record VerifyEmailResponse(String status) {}
