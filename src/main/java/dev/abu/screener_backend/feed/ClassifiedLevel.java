package dev.abu.screener_backend.feed;

public record ClassifiedLevel(
        double price,
        double quantity,
        int tier,
        long firstSeenMillis,
        double distance
) {}
