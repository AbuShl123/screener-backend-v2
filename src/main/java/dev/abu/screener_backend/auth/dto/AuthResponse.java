package dev.abu.screener_backend.auth.dto;

public record AuthResponse(String accessToken, String refreshToken, long expiresIn) {}
