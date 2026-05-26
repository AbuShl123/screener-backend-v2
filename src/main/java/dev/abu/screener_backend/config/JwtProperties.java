package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "screener.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenExpiry,
        Duration refreshTokenExpiry
) {}
