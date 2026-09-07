package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.spi.RecoverySink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

@Slf4j
@Component
public class BinanceSpotSyncStrategy extends BinanceDepthSyncStrategy {

    public BinanceSpotSyncStrategy(RecoverySink recoverSink) {
        super(recoverSink);
    }

    @Override
    protected CheckResult check(JsonParser p, BinanceSyncContext ctx) {
        long u = -1, bigU = -1;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "u" -> u = p.getLongValue();
                case "U" -> bigU = p.getLongValue();
                default -> p.skipChildren();
            }

            if (u != -1 && bigU != -1) break;
        }

        if (u == -1 || bigU == -1) {
            throw new IllegalStateException("u/U not found before END_OBJECT — Binance field order changed?");
        }

        // Binance Docs state that for SPOT, events where u<=snapshotId should be discarded,
        // BUT when this rule is used in practice, SPOT orderbooks NEVER sync - snapshotId
        // very often equals to 'u' in the buffered events. Therefore, applying STRICT comparison,
        // just like in the FUTURES docs:

        if (u < ctx.lastUpdateId) {
            return CheckResult.IGNORE;
        } else if (ctx.lastUpdateId + 1 >= bigU) {
            ctx.lastUpdateId = u;
            return CheckResult.OK;
        } else {
            ctx.lastUpdateId = -1;
            return CheckResult.DE_SYNCED;
        }
    }
}
