package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.spi.RecoverySink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

@Slf4j
@Component
public class BinanceFuturesSyncStrategy extends BinanceDepthSyncStrategy {

    public BinanceFuturesSyncStrategy(RecoverySink recoverSink) {
        super(recoverSink);
    }

    @Override
    protected CheckResult check(JsonParser p, BinanceSyncContext ctx) {
        long pu = -1, u = -1, bigU = -1;
        boolean flag = ctx.syncPointFound;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();

            if (field.equals("pu")) {
                pu = p.getLongValue();
            } else if (field.equals("u")) {
                u = p.getLongValue();
            } else if (!flag && field.equals("U")) {
                bigU = p.getLongValue();
            } else {
                p.skipChildren();
            }

            if (pu != -1 && u != -1 && (flag || bigU != -1)) break;
        }

        if (pu == -1 || u == -1 || (!flag && bigU == -1)) {
            throw new IllegalStateException("u/U/pu not found before END_OBJECT — Binance field order changed?");
        }

        if (!flag) {
            if (u < ctx.lastUpdateId) {
                return CheckResult.IGNORE;
            } else if (bigU <= ctx.lastUpdateId) {
                ctx.lastUpdateId = u;
                ctx.syncPointFound = true;
                return CheckResult.OK;
            } else {
                return CheckResult.DE_SYNCED;
            }
        }

        if (ctx.lastUpdateId == pu) {
            ctx.lastUpdateId = u;
            return CheckResult.OK;
        } else {
            return CheckResult.DE_SYNCED;
        }
    }
}
