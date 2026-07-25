package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.monitoring.dto.UsageReportResponse;
import dev.abu.screener_backend.monitoring.dto.UsageReportResponse.UsageSlice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
     * @param sliceIso {@code PT1M}-and-up ISO-8601 duration (e.g. {@code PT30M}, {@code PT1H}, {@code P7D}),
     *                 or the calendar-unit literals {@code P1M} / {@code P1Y} for true calendar-month /
     *                 calendar-year buckets (a fixed-width duration can't represent "1 month" — months
     *                 run 28-31 days)
     * @param zoneId   IANA zone id
     */
    @Transactional(readOnly = true)
    public UsageReportResponse report(LocalDate start, LocalDate end, String sliceIso, String zoneId) {
        ZoneId zone = parseZone(zoneId);
        CalendarUnit calendarUnit = parseCalendarUnit(sliceIso);

        // Default dates AFTER resolving the zone, so "today" means today in `zone`.
        if (start == null) start = LocalDate.now(zone);
        if (end == null) end = LocalDate.now(zone);
        if (end.isBefore(start)) {
            throw ApiException.badRequest("end date must not be before start date");
        }

        Instant windowStart = start.atStartOfDay(zone).toInstant();
        Instant windowEnd = end.plusDays(1).atStartOfDay(zone).toInstant();   // end date inclusive

        List<UsageSlice> slices;
        String echoedSlice;
        if (calendarUnit != null) {
            slices = calendarSlices(windowStart, windowEnd, zone, calendarUnit);
            echoedSlice = calendarUnit.iso();
        } else {
            Duration slice = parseDuration(sliceIso);
            slices = fixedSlices(windowStart, windowEnd, zone, slice);
            echoedSlice = slice.toString();
        }

        long totalConnections = 0;
        UsageSlice mostActive = null;
        for (UsageSlice usageSlice : slices) {
            totalConnections += usageSlice.uniqueConnections();
            // Rows are ordered by bucket, so a strict > keeps the earliest slice on ties.
            if (mostActive == null || usageSlice.uniqueConnections() > mostActive.uniqueConnections()) {
                mostActive = usageSlice;
            }
        }

        return new UsageReportResponse(
                windowStart.atZone(zone).toOffsetDateTime(),
                windowEnd.atZone(zone).toOffsetDateTime(),
                zone.getId(),
                echoedSlice,
                slices.size(),
                totalConnections,
                mostActive,
                slices);
    }

    private List<UsageSlice> fixedSlices(Instant windowStart, Instant windowEnd, ZoneId zone, Duration slice) {
        long sliceSeconds = slice.getSeconds();
        long windowStartEpoch = windowStart.getEpochSecond();

        List<Object[]> rows = connectionEventRepository.aggregateBySlice(
                windowStart, windowEnd, windowStartEpoch, sliceSeconds);

        List<UsageSlice> slices = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            long bucketIndex = ((Number) row[0]).longValue();
            long unique = ((Number) row[1]).longValue();

            Instant sliceStart = windowStart.plusSeconds(bucketIndex * sliceSeconds);
            Instant sliceEnd = sliceStart.plusSeconds(sliceSeconds);
            slices.add(new UsageSlice(
                    sliceStart.atZone(zone).toOffsetDateTime(),
                    sliceEnd.atZone(zone).toOffsetDateTime(),
                    unique));
        }
        return slices;
    }

    private List<UsageSlice> calendarSlices(Instant windowStart, Instant windowEnd, ZoneId zone, CalendarUnit unit) {
        List<Object[]> rows = connectionEventRepository.aggregateByCalendarUnit(
                windowStart, windowEnd, unit.sqlUnit(), zone.getId());

        List<UsageSlice> slices = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Instant bucketStart = toInstant(row[0]);
            long unique = ((Number) row[1]).longValue();

            ZonedDateTime zonedStart = bucketStart.atZone(zone);
            ZonedDateTime zonedEnd = unit.plusOne(zonedStart);
            slices.add(new UsageSlice(zonedStart.toOffsetDateTime(), zonedEnd.toOffsetDateTime(), unique));
        }
        return slices;
    }

    /**
     * The driver maps the {@code timestamptz} expression from {@code aggregateByCalendarUnit} to
     * either {@link Instant} or {@link Timestamp} depending on JDBC driver/version — unlike the
     * {@code bigint} columns elsewhere, native-query timestamp mapping isn't stable across drivers.
     */
    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case java.time.OffsetDateTime odt -> odt.toInstant();
            default -> throw new IllegalStateException(
                    "Unexpected bucket_start type: " + value.getClass());
        };
    }

    private static ZoneId parseZone(String zoneId) {
        try {
            return ZoneId.of(zoneId);
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid zone: " + zoneId);
        }
    }

    private static CalendarUnit parseCalendarUnit(String sliceIso) {
        return switch (sliceIso) {
            case "P1M" -> CalendarUnit.MONTH;
            case "P1Y" -> CalendarUnit.YEAR;
            default -> null;
        };
    }

    private static Duration parseDuration(String sliceIso) {
        Duration slice;
        try {
            slice = Duration.parse(sliceIso);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(
                    "Invalid slice (use an ISO-8601 duration like PT30M, or the calendar literals P1M/P1Y): "
                            + sliceIso);
        }
        if (slice.compareTo(MIN_SLICE) < 0) {
            throw ApiException.badRequest("slice must be at least " + MIN_SLICE);
        }
        return slice;
    }

    /** The two calendar-bucket literals accepted alongside fixed-width ISO-8601 durations. */
    private enum CalendarUnit {
        MONTH("P1M", "month") {
            @Override
            ZonedDateTime plusOne(ZonedDateTime start) {
                return start.plusMonths(1);
            }
        },
        YEAR("P1Y", "year") {
            @Override
            ZonedDateTime plusOne(ZonedDateTime start) {
                return start.plusYears(1);
            }
        };

        private final String iso;
        private final String sqlUnit;

        CalendarUnit(String iso, String sqlUnit) {
            this.iso = iso;
            this.sqlUnit = sqlUnit;
        }

        String iso() {
            return iso;
        }

        /** Postgres {@code date_trunc} unit name for this literal. */
        String sqlUnit() {
            return sqlUnit;
        }

        abstract ZonedDateTime plusOne(ZonedDateTime start);
    }
}
