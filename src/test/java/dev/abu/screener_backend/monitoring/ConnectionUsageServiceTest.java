package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.monitoring.dto.UsageReportResponse;
import dev.abu.screener_backend.monitoring.dto.UsageReportResponse.UsageSlice;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConnectionUsageService} — bucket→slice projection into a non-UTC zone,
 * most-active selection and its tie-break, empty-range handling, and the four validation errors. The
 * repository is a reflective proxy returning canned {@code Object[]} rows (the codebase avoids Mockito).
 */
class ConnectionUsageServiceTest {

    private static final String TASHKENT = "Asia/Tashkent";   // fixed +05:00, no DST

    /** Builds a service whose repository returns the given canned aggregation rows. */
    private static ConnectionUsageService serviceReturning(Supplier<List<Object[]>> rows) {
        ConnectionEventRepository repo = (ConnectionEventRepository) Proxy.newProxyInstance(
                ConnectionEventRepository.class.getClassLoader(),
                new Class<?>[]{ConnectionEventRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "aggregateBySlice", "aggregateByCalendarUnit" -> rows.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new ConnectionUsageService(repo);
    }

    private static Object[] row(long bucketIndex, long unique) {
        return new Object[]{bucketIndex, unique};
    }

    /** Mirrors what the JDBC driver actually returns for the {@code timestamptz} bucket_start column. */
    private static Object[] calendarRow(java.time.OffsetDateTime bucketStart, long unique) {
        return new Object[]{bucketStart.toInstant(), unique};
    }

    /** Some driver versions map the same column to {@link java.sql.Timestamp} instead of {@link java.time.Instant}. */
    private static Object[] calendarRowAsSqlTimestamp(java.time.OffsetDateTime bucketStart, long unique) {
        return new Object[]{java.sql.Timestamp.from(bucketStart.toInstant()), unique};
    }

    @Test
    void projectsBucketsIntoRequestedZoneAndSumsTotals() {
        // 2026-07-18 in Tashkent, 30-min slices. Bucket 30 = 15:00 local, bucket 36 = 18:00 local.
        ConnectionUsageService service = serviceReturning(() -> List.of(row(30, 3), row(36, 5)));

        UsageReportResponse r = service.report(
                LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 18), "PT30M", TASHKENT);

        assertEquals(TASHKENT, r.zone());
        assertEquals("PT30M", r.slice());
        assertEquals(OffsetDateTime.parse("2026-07-18T00:00+05:00"), r.start());
        assertEquals(OffsetDateTime.parse("2026-07-19T00:00+05:00"), r.end());   // end date inclusive
        assertEquals(2, r.sliceCount());
        assertEquals(8, r.totalConnections());                                    // 3 + 5

        UsageSlice first = r.slices().get(0);
        assertEquals(OffsetDateTime.parse("2026-07-18T15:00+05:00"), first.start());
        assertEquals(OffsetDateTime.parse("2026-07-18T15:30+05:00"), first.end());
        assertEquals(3, first.uniqueConnections());

        UsageSlice second = r.slices().get(1);
        assertEquals(OffsetDateTime.parse("2026-07-18T18:00+05:00"), second.start());
        assertEquals(5, second.uniqueConnections());

        // most-active is the 18:00 slice
        assertEquals(OffsetDateTime.parse("2026-07-18T18:00+05:00"), r.mostActive().start());
        assertEquals(5, r.mostActive().uniqueConnections());
    }

    @Test
    void mostActiveTieBreaksToEarliestSlice() {
        // Two equal peaks — rows arrive ordered, earliest must win.
        ConnectionUsageService service = serviceReturning(() -> List.of(row(30, 5), row(36, 5)));

        UsageReportResponse r = service.report(
                LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 18), "PT30M", TASHKENT);

        assertEquals(OffsetDateTime.parse("2026-07-18T15:00+05:00"), r.mostActive().start());
        assertEquals(10, r.totalConnections());
    }

    @Test
    void emptyRangeReturnsZeroTotalsAndNullMostActive() {
        ConnectionUsageService service = serviceReturning(List::of);

        UsageReportResponse r = service.report(
                LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 18), "PT30M", TASHKENT);

        assertEquals(0, r.sliceCount());
        assertEquals(0, r.totalConnections());
        assertTrue(r.slices().isEmpty());
        assertNull(r.mostActive());
    }

    @Test
    void nullDatesDefaultToTodayInZone() {
        ConnectionUsageService service = serviceReturning(List::of);

        UsageReportResponse r = service.report(null, null, "PT30M", TASHKENT);

        LocalDate today = LocalDate.now(java.time.ZoneId.of(TASHKENT));
        assertEquals(today.atStartOfDay(java.time.ZoneId.of(TASHKENT)).toOffsetDateTime(), r.start());
        assertEquals(today.plusDays(1).atStartOfDay(java.time.ZoneId.of(TASHKENT)).toOffsetDateTime(), r.end());
    }

    @Test
    void rejectsEndBeforeStart() {
        ConnectionUsageService service = serviceReturning(List::of);
        assertThrows(ApiException.class, () -> service.report(
                LocalDate.of(2026, 7, 19), LocalDate.of(2026, 7, 18), "PT30M", TASHKENT));
    }

    @Test
    void rejectsUnparseableSlice() {
        ConnectionUsageService service = serviceReturning(List::of);
        assertThrows(ApiException.class, () -> service.report(null, null, "30 minutes", TASHKENT));
    }

    @Test
    void rejectsSliceBelowOneMinute() {
        ConnectionUsageService service = serviceReturning(List::of);
        assertThrows(ApiException.class, () -> service.report(null, null, "PT30S", TASHKENT));
    }

    @Test
    void rejectsInvalidZone() {
        ConnectionUsageService service = serviceReturning(List::of);
        assertThrows(ApiException.class, () -> service.report(null, null, "PT30M", "Mars/Olympus"));
    }

    @Test
    void p1mSlicesByCalendarMonthNotFixedDuration() {
        // Jan (31d), Feb (28d, non-leap 2026) — a fixed-duration slice couldn't represent either
        // correctly, but calendar-month buckets should span each month's actual local boundaries.
        ConnectionUsageService service = serviceReturning(() -> List.of(
                calendarRow(OffsetDateTime.parse("2026-01-01T00:00+05:00"), 10),
                calendarRow(OffsetDateTime.parse("2026-02-01T00:00+05:00"), 7)));

        UsageReportResponse r = service.report(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), "P1M", TASHKENT);

        assertEquals("P1M", r.slice());
        assertEquals(2, r.sliceCount());
        assertEquals(17, r.totalConnections());

        UsageSlice jan = r.slices().get(0);
        assertEquals(OffsetDateTime.parse("2026-01-01T00:00+05:00"), jan.start());
        assertEquals(OffsetDateTime.parse("2026-02-01T00:00+05:00"), jan.end());   // 31-day month
        assertEquals(10, jan.uniqueConnections());

        UsageSlice feb = r.slices().get(1);
        assertEquals(OffsetDateTime.parse("2026-02-01T00:00+05:00"), feb.start());
        assertEquals(OffsetDateTime.parse("2026-03-01T00:00+05:00"), feb.end());   // 28-day month
        assertEquals(7, feb.uniqueConnections());
    }

    @Test
    void p1ySlicesByCalendarYear() {
        ConnectionUsageService service = serviceReturning(() -> List.<Object[]>of(
                calendarRow(OffsetDateTime.parse("2026-01-01T00:00+05:00"), 42)));

        UsageReportResponse r = service.report(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "P1Y", TASHKENT);

        assertEquals("P1Y", r.slice());
        UsageSlice year = r.slices().get(0);
        assertEquals(OffsetDateTime.parse("2026-01-01T00:00+05:00"), year.start());
        assertEquals(OffsetDateTime.parse("2027-01-01T00:00+05:00"), year.end());
        assertEquals(42, year.uniqueConnections());
    }

    @Test
    void p1mAcceptsSqlTimestampRowsToo() {
        // Regression: some driver versions map the timestamptz column to java.sql.Timestamp rather
        // than java.time.Instant — both must be accepted without a ClassCastException.
        ConnectionUsageService service = serviceReturning(() -> List.<Object[]>of(
                calendarRowAsSqlTimestamp(OffsetDateTime.parse("2026-01-01T00:00+05:00"), 5)));

        UsageReportResponse r = service.report(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "P1M", TASHKENT);

        assertEquals(OffsetDateTime.parse("2026-01-01T00:00+05:00"), r.slices().get(0).start());
        assertEquals(5, r.slices().get(0).uniqueConnections());
    }
}
