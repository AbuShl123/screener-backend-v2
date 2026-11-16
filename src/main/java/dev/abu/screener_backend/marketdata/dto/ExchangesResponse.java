package dev.abu.screener_backend.marketdata.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.abu.screener_backend.marketdata.DiscoveryStatus;
import dev.abu.screener_backend.marketdata.Market;

import java.util.List;

/**
 * {@code GET /api/exchanges} (v1): every exchange with at least one enabled venue, in enum
 * declaration order.
 *
 * @param exchanges the exchanges, each with only its enabled markets
 */
public record ExchangesResponse(List<ExchangeView> exchanges) {

    /**
     * @param id      the exchange identifier, identical to the WebSocket feed's {@code exchange}
     * @param label   display name
     * @param logoUrl always {@code null} in v1; always serialized
     * @param markets enabled markets, in {@link Market} declaration order; never empty
     */
    public record ExchangeView(
            String id,
            String label,
            @JsonInclude(JsonInclude.Include.ALWAYS) String logoUrl,
            List<MarketView> markets
    ) {}

    /**
     * @param market          {@code SPOT} or {@code FUTURES}
     * @param instrumentCount instruments in the venue's active universe; 0 until discovery succeeds
     * @param status          discovery status (not WebSocket connection health)
     */
    public record MarketView(Market market, int instrumentCount, MarketStatus status) {}

    /** @param discovery outcome of the venue's last discovery attempt */
    public record MarketStatus(DiscoveryStatus discovery) {}
}
