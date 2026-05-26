package dev.abu.screener_backend.auth;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, String email, String role) {}
