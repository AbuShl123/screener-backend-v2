package dev.abu.screener_backend.binance.websocket;

public enum Market {
    SPOT {
        @Override
        public String streamSuffix() { return "@depth"; }
    },
    FUTURES {
        @Override
        public String streamSuffix() { return "@depth@500ms"; }
    };

    public abstract String streamSuffix();
}
