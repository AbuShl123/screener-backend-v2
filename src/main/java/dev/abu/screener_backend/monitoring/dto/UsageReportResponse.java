package dev.abu.screener_backend.monitoring.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response body for {@code GET /api/monitoring/usage} — connection activity aggregated into
 * time-slices over a date range. All timestamps are projected into the report's {@code zone}.
 *
 * @param start            window start (inclusive), in {@code zone}
 * @param end              window end (exclusive), in {@code zone}
 * @param zone             IANA zone id used to place slice boundaries and label output
 * @param slice            echoed ISO-8601 slice duration, e.g. {@code "PT30M"}
 * @param sliceCount       number of non-empty slices returned
 * @param totalConnections sum of {@code uniqueConnections} across all returned slices (a user active in
 *                         two slices counts in both — this is <b>not</b> distinct-users-over-range)
 * @param mostActive       the slice with the most unique connections; {@code null} when the range is empty
 * @param slices           non-empty slices, ordered by start
 */
public record UsageReportResponse(
        OffsetDateTime start,
        OffsetDateTime end,
        String zone,
        String slice,
        int sliceCount,
        long totalConnections,
        UsageSlice mostActive,
        List<UsageSlice> slices) {

    public record UsageSlice(OffsetDateTime start, OffsetDateTime end, long uniqueConnections) {}
}
