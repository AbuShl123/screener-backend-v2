package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.monitoring.dto.UsageReportResponse;
import dev.abu.screener_backend.monitoring.dto.UsageReportResponse.UsageSlice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read/report path for persisted usage analytics. Aggregates {@code connection_events} into
 * time-slices for {@code GET /api/monitoring/usage}.
 *
 * <p>Storage is always UTC; {@code zone} is a query-time lens that decides where slice boundaries fall
 * (local midnight, local :00/:30) and labels the output. Changing it re-slices the same stored data
 * with no migration.
 */
@Service
@RequiredArgsConstructor
public class ConnectionUsageService {

    /** Guards against divide-by-zero and absurdly fine slices. */
    private static final Duration MIN_SLICE = Duration.ofMinutes(1);

    private final ConnectionEventRepository connectionEventRepository;

    /**
     * @param start    inclusive calendar date in {@code zoneId}; {@code null} → today in {@code zone}
     * @param end      inclusive calendar date in {@code zoneId}; {@code null} → today in {@code zone}
     * @param sliceIso ISO-8601 duration, {@code >= PT1M} (e.g. {@code PT30M}, {@code PT1H}, {@code P7D})
     * @param zoneId   IANA zone id
     */
    @Transactional(readOnly = true)
    public UsageReportResponse report(LocalDate start, LocalDate end, String sliceIso, String zoneId) {
        ZoneId zone = parseZone(zoneId);
        Duration slice = parseSlice(sliceIso);

        // Default dates AFTER resolving the zone, so "today" means today in `zone`.
        if (start == null) start = LocalDate.now(zone);
        if (end == null) end = LocalDate.now(zone);
        if (end.isBefore(start)) {
            throw ApiException.badRequest("end date must not be before start date");
        }

        Instant windowStart = start.atStartOfDay(zone).toInstant();
        Instant windowEnd = end.plusDays(1).atStartOfDay(zone).toInstant();   // end date inclusive
        long sliceSeconds = slice.getSeconds();
        long windowStartEpoch = windowStart.getEpochSecond();

        List<Object[]> rows = connectionEventRepository.aggregateBySlice(
                windowStart, windowEnd, windowStartEpoch, sliceSeconds);

        List<UsageSlice> slices = new ArrayList<>(rows.size());
        long totalConnections = 0;
        UsageSlice mostActive = null;
        for (Object[] row : rows) {
            long bucketIndex = ((Number) row[0]).longValue();
            long unique = ((Number) row[1]).longValue();

            Instant sliceStart = windowStart.plusSeconds(bucketIndex * sliceSeconds);
            Instant sliceEnd = sliceStart.plusSeconds(sliceSeconds);
            UsageSlice usageSlice = new UsageSlice(
                    sliceStart.atZone(zone).toOffsetDateTime(),
                    sliceEnd.atZone(zone).toOffsetDateTime(),
                    unique);
            slices.add(usageSlice);

            totalConnections += unique;
            // Rows are ordered by bucket, so a strict > keeps the earliest slice on ties.
            if (mostActive == null || unique > mostActive.uniqueConnections()) {
                mostActive = usageSlice;
            }
        }

        return new UsageReportResponse(
                windowStart.atZone(zone).toOffsetDateTime(),
                windowEnd.atZone(zone).toOffsetDateTime(),
                zone.getId(),
                slice.toString(),
                slices.size(),
                totalConnections,
                mostActive,
                slices);
    }

    private static ZoneId parseZone(String zoneId) {
        try {
            return ZoneId.of(zoneId);
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid zone: " + zoneId);
        }
    }

    private static Duration parseSlice(String sliceIso) {
        Duration slice;
        try {
            slice = Duration.parse(sliceIso);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid slice duration (use ISO-8601, e.g. PT30M): " + sliceIso);
        }
        if (slice.compareTo(MIN_SLICE) < 0) {
            throw ApiException.badRequest("slice must be at least " + MIN_SLICE);
        }
        return slice;
    }
}
