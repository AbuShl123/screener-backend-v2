package dev.abu.screener_backend.monitoring;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ConnectionEventRepository extends JpaRepository<ConnectionEvent, Long> {

    /**
     * Aggregates connects into fixed-width time slices, counting distinct users per slice.
     *
     * <p>Bucket index is anchored to {@code windowStartEpoch} (local midnight of the report's start
     * date, in seconds) rather than the Unix epoch, so slice boundaries land on <b>local</b>
     * :00/:30/day lines in whatever zone the caller picked. Only non-empty buckets come back, ordered.
     *
     * <p>{@code EXTRACT(EPOCH FROM connected_at)} yields unambiguous Unix seconds; subtracting the plain
     * {@code long} anchor avoids any {@code timestamptz}/{@code timestamp}-without-zone binding ambiguity.
     * The only {@link Instant} params are the {@code WHERE} range bounds (the standard, safe pattern).
     *
     * @return rows of {@code [bucketIndex (bigint), uniqueConnections (bigint)]}, ordered by bucket
     */
    @Query(value = """
            SELECT FLOOR((EXTRACT(EPOCH FROM connected_at) - :windowStartEpoch) / :sliceSeconds)::bigint
                       AS bucket_index,
                   COUNT(DISTINCT user_id) AS unique_connections
            FROM connection_events
            WHERE connected_at >= :windowStart AND connected_at < :windowEnd
            GROUP BY bucket_index
            ORDER BY bucket_index
            """, nativeQuery = true)
    List<Object[]> aggregateBySlice(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("windowStartEpoch") long windowStartEpoch,
            @Param("sliceSeconds") long sliceSeconds);
}
