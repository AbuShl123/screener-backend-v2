package dev.abu.screener_backend.marketdata.adapter.bybit;

import org.springframework.context.annotation.Configuration;

/**
 * The Bybit adapter's registration point: spot ({@code category=spot}) and futures (linear USDT
 * perpetuals, {@code category=linear}).
 *
 * <p>Like {@code BinanceAdapterConfig} and {@code MexcAdapterConfig}, every adapter bean is
 * constructed here and never component-scanned; a stray {@code @Component} would yield a duplicate
 * binding or a second claim on a Bybit venue at startup. {@code bybit.enabled} is the master switch
 * and each venue has its own.
 *
 * <p>Empty until the adapter lands: no {@code InstrumentSource}, stream binding or strategy binding
 * is registered yet, so {@code screener.exchanges.bybit.enabled} must stay {@code false} — an enabled
 * venue with no stream binding fails startup. The plan is
 * {@code .claude/plans/team-plans/bybit-exchange-plan.md}.
 */
@Configuration
public class BybitAdapterConfig {
}
