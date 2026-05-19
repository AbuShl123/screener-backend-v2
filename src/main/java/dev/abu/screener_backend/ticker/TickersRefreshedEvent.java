package dev.abu.screener_backend.ticker;

import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.Map;

public class TickersRefreshedEvent extends ApplicationEvent {

    private final Map<String, Ticker> tickers;

    public TickersRefreshedEvent(Object source, Map<String, Ticker> tickers) {
        super(source);
        this.tickers = Collections.unmodifiableMap(tickers);
    }

    public Map<String, Ticker> getTickers() {
        return tickers;
    }
}
