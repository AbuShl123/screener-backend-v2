package dev.abu.screener_backend.billing.dto;

/** Response for the public pay-as-you-go days estimate: how many days {@code amount} buys. */
public record PayAsYouGoDaysResponse(long days) {}
