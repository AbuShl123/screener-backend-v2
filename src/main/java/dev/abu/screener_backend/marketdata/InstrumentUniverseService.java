package dev.abu.screener_backend.marketdata;

import dev.abu.screener_backend.config.DiscoveryProperties;
import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.marketdata.core.book.BookSlotTable;
import dev.abu.screener_backend.marketdata.spi.InstrumentCandidate;
import dev.abu.screener_backend.marketdata.spi.InstrumentSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Merges every enabled {@link InstrumentSource} into one instrument universe and drives
 * registration, slot allocation and subscription.
 *
 * <p>Exchange-agnostic: what an exchange lists and which of it the pipeline can track is the
 * adapter's source's business. This class owns only what must be uniform across exchanges — the
 * exclusion list, id assignment, the added/removed diff, publication order and failure isolation.
 *
 * <h3>Exclusion</h3>
 * {@code screener.discovery.excluded-symbols} is applied here, to every source's validated result,
 * matched on the normalized {@link Instrument#symbol(String, String) symbol} ({@code base + quote})
 * so one list serves every exchange and market. Applied after validation: the empty-venue guard
 * judges what the exchange reported, not what configuration chose to drop.
 *
 * <h3>Sources</h3>
 * Validated once at construction: every source claims a non-empty set of venues of one exchange,
 * and no venue is claimed twice. A source whose venues are all disabled
 * ({@link ExchangesProperties#isEnabled}) is skipped; a partially-enabled one is a configuration
 * error, because a source cannot be asked to fetch a subset of its venues.
 *
 * <h3>Ordering invariant</h3>
 * {@code register all → allocate slots → publish → fire event → transport subscribes.} The event
 * listener runs synchronously on this thread, so the ordering holds naturally — but it is asserted
 * here rather than assumed. See {@link BookSlotTable}.
 *
 * <p>Ids are assigned on the calling thread in {@code (venue.ordinal(), nativeSymbol)} order, so
 * they are reproducible across restarts regardless of the order sources report in.
 *
 * <h3>Failure behaviour</h3>
 * Isolated per source. Sources are fetched concurrently, each bounded by
 * {@code screener.discovery.source-timeout}. A source that throws, times out, reports the wrong
 * venues, or empties a previously non-empty venue is treated as failed: its venues keep their
 * previous universe and contribute no removals, while the other sources' changes apply normally.
 * A network blip must never be read as a mass delisting.
 */
@Slf4j
@Service
public class InstrumentUniverseService {

    private static final Comparator<PlacedCandidate> ID_ORDER = Comparator
            .<PlacedCandidate>comparingInt(c -> c.venue().ordinal())
            .thenComparing(c -> c.candidate().nativeSymbol());

    private final List<SourceHandle> sources;
    private final InstrumentRegistry registry;
    private final BookSlotTable slots;
    private final Duration sourceTimeout;
    private final Set<String> excludedSymbols;
    private final ApplicationEventPublisher eventPublisher;

    /** Ids per venue as of that venue's last successful fetch. Discovery thread only. */
    private Map<Venue, Set<Integer>> activeByVenue = new EnumMap<>(Venue.class);

    public InstrumentUniverseService(List<InstrumentSource> sources,
                                     InstrumentRegistry registry,
                                     BookSlotTable slots,
                                     ExchangesProperties exchanges,
                                     DiscoveryProperties discovery,
                                     ApplicationEventPublisher eventPublisher) {
        this.sources = enabledSources(sources, exchanges);
        this.registry = registry;
        this.slots = slots;
        this.sourceTimeout = discovery.sourceTimeout();
        this.excludedSymbols = discovery.excludedSymbols();
        this.eventPublisher = eventPublisher;
    }

    /**
     * Validates every source's claim, then keeps the fully-enabled ones.
     *
     * @throws IllegalStateException on an empty or multi-exchange claim, a venue claimed twice, or a
     *         source whose venues are only partly enabled — all startup bugs
     */
    private static List<SourceHandle> enabledSources(List<InstrumentSource> sources, ExchangesProperties exchanges) {
        Map<Venue, InstrumentSource> claimedBy = new EnumMap<>(Venue.class);
        List<SourceHandle> enabled = new ArrayList<>();

        for (InstrumentSource source : sources) {
            Set<Venue> claimed = source.venues();
            if (claimed == null || claimed.isEmpty()) {
                throw new IllegalStateException("InstrumentSource " + name(source) + " claims no venues");
            }
            Set<Venue> venues = Set.copyOf(claimed);
            if (venues.stream().map(Venue::exchange).distinct().count() > 1) {
                throw new IllegalStateException("InstrumentSource " + name(source)
                        + " spans more than one exchange: " + venues);
            }
            for (Venue venue : venues) {
                InstrumentSource previous = claimedBy.put(venue, source);
                if (previous != null) {
                    throw new IllegalStateException("Venue " + venue + " is claimed by two InstrumentSources: "
                            + name(previous) + " and " + name(source)
                            + " — check for a stray @Component alongside the adapter's @Bean");
                }
            }

            long enabledCount = venues.stream().filter(exchanges::isEnabled).count();
            if (enabledCount == venues.size()) {
                enabled.add(new SourceHandle(source, venues));
            } else if (enabledCount == 0) {
                log.info("Instrument source {} skipped — venues {} are disabled", name(source), venues);
            } else {
                throw new IllegalStateException("InstrumentSource " + name(source) + " covers venues " + venues
                        + " but only some are enabled; a source cannot fetch a subset of its venues");
            }
        }
        return List.copyOf(enabled);
    }

    /**
     * Fetches every enabled source concurrently, then registers the merged universe on this thread.
     *
     * <p>Intentionally synchronous so the startup listener and the scheduler can reason about
     * completion.
     */
    public void refresh() {
        log.info("Universe refresh triggered: using {} source(s)...", sources.size());
        Map<Venue, List<InstrumentCandidate>> fresh = new EnumMap<>(Venue.class);
        Set<Venue> retained = EnumSet.noneOf(Venue.class);
        int succeeded = fetchAll(fresh, retained);
        apply(fresh, retained, succeeded);
    }

    /**
     * Runs every source's {@code fetch()} on its own virtual thread and sorts the outcomes into
     * {@code fresh} (validated results) and {@code retained} (venues of failed sources).
     *
     * <p>The executor is shut down with {@code shutdownNow()} rather than closed: {@code close()}
     * waits for every task, so one source ignoring its interrupt would stall the whole refresh.
     *
     * @return the number of sources that succeeded
     */
    private int fetchAll(Map<Venue, List<InstrumentCandidate>> fresh, Set<Venue> retained) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Map<Venue, List<InstrumentCandidate>>>> futures = new ArrayList<>(sources.size());
            for (SourceHandle handle : sources) {
                futures.add(executor.submit(handle.source()::fetch));
            }

            // All fetches start together, so one shared deadline gives each the full timeout.
            long deadline = System.nanoTime() + sourceTimeout.toNanos();
            int succeeded = 0;

            for (int i = 0; i < sources.size(); i++) {
                SourceHandle handle = sources.get(i);
                Future<Map<Venue, List<InstrumentCandidate>>> future = futures.get(i);

                try {
                    Map<Venue, List<InstrumentCandidate>> result = future.get(
                            Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);

                    String rejection = validate(handle, result);
                    if (rejection == null) {
                        accept(handle, result, fresh);
                        succeeded++;
                    } else {
                        retain(handle, retained, rejection, null);
                    }

                } catch (TimeoutException e) {
                    future.cancel(true);
                    retain(handle, retained, "timed out after " + sourceTimeout, null);
                } catch (ExecutionException e) {
                    retain(handle, retained, "fetch failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    for (int j = i; j < sources.size(); j++) {
                        retain(sources.get(j), retained, "refresh interrupted", null);
                    }
                    return succeeded;
                }
            }
            return succeeded;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * @return {@code null} if the result is usable, otherwise why it is not. A result that would
     *         empty a previously non-empty venue is rejected: a changed response shape or a bad
     *         filter value must not silently delist a whole venue.
     */
    private String validate(SourceHandle handle, Map<Venue, List<InstrumentCandidate>> result) {
        if (result == null || !result.keySet().equals(handle.venues())) {
            return "returned venues " + (result == null ? null : result.keySet())
                    + ", expected " + handle.venues();
        }
        for (Venue venue : handle.venues()) {
            List<InstrumentCandidate> candidates = result.get(venue);
            if (candidates == null) {
                return "returned no list for " + venue;
            }
            if (candidates.isEmpty() && !activeByVenue.getOrDefault(venue, Set.of()).isEmpty()) {
                return "returned an empty universe for " + venue + ", which previously had "
                        + activeByVenue.get(venue).size() + " instruments";
            }
        }
        return null;
    }

    /**
     * Applies the exclusion list to a validated result and logs what each venue will actually
     * track — the source can't, since it reports before exclusion.
     */
    private void accept(SourceHandle handle, Map<Venue, List<InstrumentCandidate>> result,
                        Map<Venue, List<InstrumentCandidate>> fresh) {
        StringJoiner counts = new StringJoiner(", ");
        int excluded = 0;
        for (Venue venue : EnumSet.copyOf(handle.venues())) {
            List<InstrumentCandidate> reported = result.get(venue);
            List<InstrumentCandidate> kept = withoutExcluded(reported);
            fresh.put(venue, kept);
            counts.add(kept.size() + " " + venue.market().name().toLowerCase());
            excluded += reported.size() - kept.size();
        }
        log.info("{} instrument universe selected: {} ({} excluded)",
                handle.venues().iterator().next().exchange(), counts, excluded);
    }

    private List<InstrumentCandidate> withoutExcluded(List<InstrumentCandidate> candidates) {
        if (excludedSymbols.isEmpty()) return candidates;
        return candidates.stream()
                .filter(c -> !excludedSymbols.contains(Instrument.symbol(c.base(), c.quote())))
                .toList();
    }

    private void retain(SourceHandle handle, Set<Venue> retained, String reason, Throwable cause) {
        retained.addAll(handle.venues());
        String previous = handle.venues().stream()
                .map(v -> v + "=" + activeByVenue.getOrDefault(v, Set.of()).size())
                .collect(Collectors.joining(", "));
        log.warn("Instrument source {} {} — retaining previous universe ({})",
                name(handle.source()), reason, previous, cause);
    }

    /** Discovery thread. Registers, allocates, publishes, then announces — in that order. */
    private void apply(Map<Venue, List<InstrumentCandidate>> fresh, Set<Venue> retained, int succeeded) {
        List<PlacedCandidate> candidates = new ArrayList<>();
        Map<Venue, Set<Integer>> current = new EnumMap<>(Venue.class);
        fresh.forEach((venue, list) -> {
            current.put(venue, new HashSet<>(list.size() * 2));
            for (InstrumentCandidate c : list) candidates.add(new PlacedCandidate(venue, c));
        });
        candidates.sort(ID_ORDER);

        List<Instrument> added = new ArrayList<>();
        for (PlacedCandidate pc : candidates) {
            InstrumentCandidate c = pc.candidate();
            boolean isNew = registry.find(pc.venue(), c.nativeSymbol()).isEmpty();
            Instrument instrument = registry.register(pc.venue(), c.nativeSymbol(), c.base(), c.quote(), c.quantityMultiplier());
            current.get(pc.venue()).add(instrument.id());
            if (isNew) {
                slots.allocate(instrument);
                added.add(instrument);
            }
        }
        for (Venue venue : retained) {
            Set<Integer> previous = activeByVenue.get(venue);
            if (previous != null) current.put(venue, previous);
        }

        // Must precede the event: subscribing before the array is visible could route a message to
        // an index past its end.
        slots.publish();

        Set<Integer> currentIds = new HashSet<>();
        current.values().forEach(currentIds::addAll);
        List<Instrument> removed = new ArrayList<>();
        for (Set<Integer> ids : activeByVenue.values()) {
            for (Integer id : ids) {
                if (!currentIds.contains(id)) {
                    Instrument instrument = registry.byId(id);
                    if (instrument != null) removed.add(instrument);
                }
            }
        }
        activeByVenue = current;

        if (succeeded == 0) {
            // Nothing fresh, so nothing can have changed. Firing anyway would also mislead the
            // transport, which starts a venue's pool from the first event that adds to it.
            if (sources.isEmpty()) {
                log.warn("No enabled instrument sources — the universe is empty");
            } else {
                log.error("Instrument universe refresh failed for every source — retaining existing data "
                        + "({} instruments)", currentIds.size());
            }
            return;
        }
        log.info("Instrument universe updated — {} tracked ({} added, {} removed)",
                currentIds.size(), added.size(), removed.size());
        eventPublisher.publishEvent(new InstrumentUniverseChangedEvent(this, added, removed));
    }

    private static String name(InstrumentSource source) {
        return source.getClass().getSimpleName();
    }

    /** A source with its validated, immutable venue claim. */
    private record SourceHandle(InstrumentSource source, Set<Venue> venues) {}

    private record PlacedCandidate(Venue venue, InstrumentCandidate candidate) {}
}
