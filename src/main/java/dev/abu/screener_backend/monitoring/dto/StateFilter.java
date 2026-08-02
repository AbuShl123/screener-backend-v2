package dev.abu.screener_backend.monitoring.dto;

import dev.abu.screener_backend.binance.orderbook.OrderBookState;

/**
 * {@code state} query parameter — which books enter the size sample / the book table.
 *
 * <p>Defaults to {@link #SYNCED} because a PENDING book holds zero levels: including it would report
 * a mean that mostly measures how many books are currently resyncing. State <i>counts</i> in the
 * response always cover every book, so nothing is hidden by the default.
 */
public enum StateFilter {

    ALL(null),
    PENDING(OrderBookState.PENDING),
    SNAPSHOT_REQUESTED(OrderBookState.SNAPSHOT_REQUESTED),
    SYNCED(OrderBookState.SYNCED);

    private final OrderBookState state;

    StateFilter(OrderBookState state) {
        this.state = state;
    }

    public boolean matches(OrderBookState candidate) {
        return state == null || state == candidate;
    }
}
