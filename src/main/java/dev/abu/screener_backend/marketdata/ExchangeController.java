package dev.abu.screener_backend.marketdata;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.marketdata.dto.ExchangesResponse;
import dev.abu.screener_backend.marketdata.dto.ExchangesResponse.ExchangeView;
import dev.abu.screener_backend.marketdata.dto.ExchangesResponse.MarketStatus;
import dev.abu.screener_backend.marketdata.dto.ExchangesResponse.MarketView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the exchanges and markets the screener streams, with per-venue discovery metadata.
 *
 * <p>Requires authentication only (no entitlement, no admin role) — covered by
 * {@code SecurityConfig}'s {@code anyRequest().authenticated()}. Read-only and cold path.
 */
@RestController
@RequestMapping("/api/exchanges")
@RequiredArgsConstructor
public class ExchangeController {

    private final ExchangesProperties exchangesProperties;
    private final InstrumentUniverseService universeService;

    /**
     * Every exchange with at least one enabled venue, in enum declaration order. An enabled venue is
     * listed even before discovery completes ({@code PENDING}, count 0).
     */
    @GetMapping
    public ExchangesResponse getExchanges() {
        List<ExchangeView> exchanges = new ArrayList<>();
        for (Exchange exchange : Exchange.values()) {
            List<MarketView> markets = new ArrayList<>();
            for (Market market : Market.values()) {
                Venue venue = venueOf(exchange, market);
                if (venue == null || !exchangesProperties.isEnabled(venue)) continue;
                VenueDiscovery d = universeService.discovery(venue);
                markets.add(new MarketView(market, d.instrumentCount(), new MarketStatus(d.status())));
            }
            if (!markets.isEmpty()) {
                exchanges.add(new ExchangeView(exchange.name(), exchange.label(), null, List.copyOf(markets)));
            }
        }
        return new ExchangesResponse(List.copyOf(exchanges));
    }

    /** The venue for {@code (exchange, market)}, or {@code null} if the exchange has no such market. */
    private static Venue venueOf(Exchange exchange, Market market) {
        for (Venue v : Venue.values()) {
            if (v.exchange() == exchange && v.market() == market) return v;
        }
        return null;
    }
}
