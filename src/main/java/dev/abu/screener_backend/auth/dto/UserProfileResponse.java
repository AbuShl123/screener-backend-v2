package dev.abu.screener_backend.auth.dto;

import java.util.UUID;

public record UserProfileResponse(UUID id, String firstName, String lastName, String email, String role) {}
