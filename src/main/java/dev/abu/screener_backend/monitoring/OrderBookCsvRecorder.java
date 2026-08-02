package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.config.MonitoringProperties;
import dev.abu.screener_backend.monitoring.dto.HistogramDomain;
import dev.abu.screener_backend.monitoring.dto.OrderBookListResponse;
import dev.abu.screener_backend.monitoring.dto.OrderBookListResponse.BookRow;
import dev.abu.screener_backend.monitoring.dto.OrderBookStatsResponse;
import dev.abu.screener_backend.monitoring.dto.ShardLoad;
import dev.abu.screener_backend.monitoring.dto.SortOrder;
import dev.abu.screener_backend.monitoring.dto.StateFilter;
import dev.abu.screener_backend.monitoring.dto.BookSort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

/**
 * Appends the orderbook fleet's state to CSV files on local disk on a fixed schedule.
 *
 * <h3>Why this exists in the server rather than as an external poller</h3>
 * Everything it writes is already available over {@code /api/monitoring/orderbook**}, so an external
 * script could do the same job. In-process is simply better for an unattended multi-day capture: no
 * admin credentials sitting in a cron environment, no 3-hour access-token expiry to work around, no
 * second process to keep alive, and no chance of the capture and the server disagreeing about what
 * "now" means. The trade-off accepted in exchange is that the recorder dies with the JVM — which is
 * fine, because a gap in the CSV is itself a recorded fact about the night.
 *
 * <h3>What it captures and why three files</h3>
 * <ul>
 *   <li>{@code books-<date>.csv} — one row per book per tick. The per-book time series, which no
 *       endpoint retains: {@code /orderbook/books} is point-in-time and the trend ring is
 *       fleet-aggregate. This is the raw material for "which books grow, and how fast".</li>
 *   <li>{@code fleet-<date>.csv} — one row per tick. Totals plus used heap, on a faster cadence,
 *       because regressing {@code usedHeapBytes} against {@code totalLevels} across thousands of
 *       points is what turns "how many levels" into "how many gigabytes at 20 exchanges".</li>
 *   <li>{@code shards-<date>.csv} — one row per shard per tick, long format. Whether the hash-based
 *       ticker→shard mapping balances actual work, which decides shard count as exchanges are
 *       added.</li>
 * </ul>
 * All three are long-format with a leading timestamp, so each loads into pandas with a bare
 * {@code read_csv} and no reshaping.
 *
 * <h3>Cost</h3>
 * Not the hot path — it reads only already-published immutable snapshots, exactly like the
 * endpoints. At ~900 books the books file is ~65 KB per write; at the default 5-minute cadence that
 * is ~19 MB per day, against which {@code retention-days} is the bound that keeps a small disk from
 * filling. Files rotate daily so the sweep can work at file granularity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "screener.monitoring.orderbook.recorder", name = "enabled",
        havingValue = "true")
public class OrderBookCsvRecorder {

    private static final String BOOKS_HEADER =
            "at,symbol,market,state,size,bids,asks,updates,updatesPerSecond,idleMs,resyncs";
    private static final String FLEET_HEADER =
            "at,books,synced,pending,snapshotRequested,totalLevels,updatesPerSecond,"
            + "levelVisitsPerSecond,staleBooks,emptyBooks,resyncsLastHour,usedHeapBytes,maxHeapBytes";
    private static final String SHARDS_HEADER =
            "at,shard,books,totalLevels,updatesPerSecond,levelVisitsPerSecond,largestBook,largestBookSize";

    private final OrderBookStatsService statsService;
    private final MonitoringProperties props;

    /** Guards the retention sweep so it runs once a day rather than on every fleet tick. */
    private LocalDate lastSweepDate = null;

    @Scheduled(fixedRateString = "${screener.monitoring.orderbook.recorder.books-interval-ms}",
            initialDelayString = "${screener.monitoring.orderbook.recorder.books-interval-ms}")
    public void recordBooks() {
        try {
            // state=ALL: a book falling out of SYNCED overnight is precisely what a long capture is
            // for, and the default SYNCED filter would make it silently vanish from the file.
            OrderBookListResponse snapshot = statsService.books(
                    null, StateFilter.ALL, null, BookSort.SYMBOL, SortOrder.ASC,
                    props.orderbook().maxBooksReturned());

            String at = snapshot.generatedAt().toInstant().toString();
            StringBuilder sb = new StringBuilder(snapshot.books().size() * 80);
            for (BookRow b : snapshot.books()) {
                sb.append(at).append(',')
                        .append(b.symbol()).append(',')
                        .append(b.market()).append(',')
                        .append(b.state()).append(',')
                        .append(b.size()).append(',')
                        .append(b.bids()).append(',')
                        .append(b.asks()).append(',')
                        .append(b.updates()).append(',')
                        .append(b.updatesPerSecond()).append(',')
                        .append(b.idleMs()).append(',')
                        .append(b.resyncs()).append('\n');
            }
            append("books", BOOKS_HEADER, sb.toString());
        } catch (Exception e) {
            // Never let a disk problem kill the scheduled task — Spring would stop rescheduling it.
            log.warn("Failed to record books CSV: {}", e.toString());
        }
    }

    @Scheduled(fixedRateString = "${screener.monitoring.orderbook.recorder.fleet-interval-ms}",
            initialDelayString = "${screener.monitoring.orderbook.recorder.fleet-interval-ms}")
    public void recordFleet() {
        try {
            OrderBookStatsResponse stats = statsService.fleetStats(
                    null, StateFilter.ALL, 0, 0, HistogramDomain.P99);
            OrderBookStatsResponse.Totals t = stats.totals();
            String at = stats.generatedAt().toInstant().toString();

            String fleetRow = String.join(",",
                    at,
                    String.valueOf(t.books()),
                    String.valueOf(state(t, "SYNCED")),
                    String.valueOf(state(t, "PENDING")),
                    String.valueOf(state(t, "SNAPSHOT_REQUESTED")),
                    String.valueOf(t.totalLevels()),
                    String.valueOf(t.updatesPerSecond()),
                    String.valueOf(t.levelVisitsPerSecond()),
                    String.valueOf(t.staleBooks()),
                    String.valueOf(t.emptyBooks()),
                    String.valueOf(t.resyncsLastHour()),
                    String.valueOf(t.usedHeapBytes()),
                    String.valueOf(t.maxHeapBytes())) + "\n";
            append("fleet", FLEET_HEADER, fleetRow);

            StringBuilder sb = new StringBuilder(stats.byShard().size() * 80);
            for (ShardLoad s : stats.byShard()) {
                sb.append(at).append(',')
                        .append(s.shard()).append(',')
                        .append(s.books()).append(',')
                        .append(s.totalLevels()).append(',')
                        .append(s.updatesPerSecond()).append(',')
                        .append(s.levelVisitsPerSecond()).append(',')
                        .append(s.largestBook() == null ? "" : s.largestBook()).append(',')
                        .append(s.largestBookSize()).append('\n');
            }
            append("shards", SHARDS_HEADER, sb.toString());

            sweepIfDue();
        } catch (Exception e) {
            log.warn("Failed to record fleet CSV: {}", e.toString());
        }
    }

    private static int state(OrderBookStatsResponse.Totals t, String name) {
        return t.byState().entrySet().stream()
                .filter(e -> e.getKey().name().equals(name))
                .mapToInt(java.util.Map.Entry::getValue)
                .findFirst().orElse(0);
    }

    /** Append to {@code <prefix>-<today>.csv}, writing the header only when creating the file. */
    private void append(String prefix, String header, String body) throws IOException {
        if (body.isEmpty()) return;

        Path dir = Path.of(props.orderbook().recorder().directory());
        Files.createDirectories(dir);
        Path file = dir.resolve(prefix + "-" + LocalDate.now(ZoneOffset.UTC) + ".csv");

        boolean fresh = Files.notExists(file);
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (fresh) {
                w.write(header);
                w.write('\n');
                log.info("Recording orderbook CSV to {}", file.toAbsolutePath());
            }
            w.write(body);
        }
    }

    /** Delete captured files whose last-modified time is older than {@code retention-days}. */
    private void sweepIfDue() {
        int retentionDays = props.orderbook().recorder().retentionDays();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (retentionDays <= 0 || today.equals(lastSweepDate)) return;
        lastSweepDate = today;

        Path dir = Path.of(props.orderbook().recorder().directory());
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86_400L);
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> stale = files
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
            for (Path p : stale) {
                Files.deleteIfExists(p);
                log.info("Deleted captured monitoring file older than {} days: {}", retentionDays, p);
            }
        } catch (IOException e) {
            log.warn("Retention sweep of {} failed: {}", dir, e.toString());
        }
    }
}
