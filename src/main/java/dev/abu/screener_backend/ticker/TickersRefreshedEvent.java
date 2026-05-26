package dev.abu.screener_backend.ticker;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.Map;

@Getter
public class TickersRefreshedEvent extends ApplicationEvent {

    private final Map<String, Ticker> tickers;

    public TickersRefreshedEvent(Object source, Map<String, Ticker> tickers) {
        super(source);
        this.tickers = Collections.unmodifiableMap(tickers);
    }

}
