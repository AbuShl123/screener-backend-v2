# Domain Requirements

**As of**: 2026-10-23.

This file lists the screener's domain requirements. These rules come from outside the product: how
exchanges work, how markets behave, how traders read an order book, and what payments require. They
hold whatever the design is. Functional requirements and the design decisions that respond to them
(`ruleKey`, the ±10% price filter, top 5 per side) belong elsewhere. See
[`requirements-foundation.md`](requirements-foundation.md) for how the categories are split.

---

## 1. Terms and units

DR-1. A venue is one market (spot or perpetual futures) on one exchange. An instrument is one
tradable symbol on one venue.

DR-2. A price level is the total resting quantity at one price on one side of the book. The book
cannot separate the orders that make up a level, so the screener shall present a level as liquidity
at a price, never as a single order.

DR-3. Notional is price × quantity, with quantity in the base asset, expressed in the quote currency.

DR-4. Mid price is (best bid + best ask) / 2. A level's distance is |price − mid| / mid, expressed as
a fraction (0.05 = 5%). Converting it to a percentage is a display concern.

DR-5. All timestamps are UTC, in epoch milliseconds.

DR-6. In a trade, the taker is the aggressor. A taker buy lifts the asks and a taker sell hits the
bids.

---

## 2. Instruments

DR-7. The screener supports spot pairs and linear USDT-margined perpetual futures quoted in USDT.
Inverse (coin-margined) contracts, delivery futures, options and other quote currencies are out of
scope, because notional means something different for them.

DR-8. The same symbol on different venues is a different instrument with its own book. BTCUSDT on
Binance spot, Binance futures, MEXC spot and MEXC futures are four instruments.

DR-9. Quantities shall be converted to the base asset before notional is computed. MEXC futures depth
is quoted in contracts and is multiplied by the contract's `contractSize`. Binance futures and both
spot venues already quote the base asset.

DR-10. USDT is treated as equal to USD for every notional threshold.

DR-11. Pairs whose price is pegged to a stable value (USDCUSDT, FDUSDUSDT, DAIUSDT, PYUSDUSDT,
USD1USDT, XAUTUSDT) are excluded on every exchange. Their books hold large, permanent levels near mid
that carry no signal.

DR-12. Only instruments the exchange reports as trading are eligible. An instrument that is halted,
delisted or no longer listed leaves the universe on the next refresh, and its data stops being shown.

DR-13. A matching symbol name does not guarantee the same asset across venues. A futures contract can
list a multiple of the base asset (Binance `1000PEPEUSDT` is priced per 1000 PEPE), and two exchanges
can use one ticker for unrelated tokens. Any feature that links instruments across venues by name
shall account for both cases.

---

## 3. Order book semantics

DR-14. A local book is built from a REST snapshot plus the stream of diffs that follow it. A diff
replaces the quantity at a price. It does not add to it.

DR-15. A quantity of zero removes the level.

DR-16. Binance spot: diffs with `u` ≤ the snapshot's `lastUpdateId` are dropped. The first applied
diff must satisfy `U` ≤ `lastUpdateId` + 1 ≤ `u`, and each later diff must have `U` = previous `u` + 1.

DR-17. Binance futures: diffs with `u` < the snapshot's `lastUpdateId` are dropped. The first applied
diff must satisfy `U` ≤ `lastUpdateId` ≤ `u`, and each later diff must have `pu` = previous `u`.

DR-18. MEXC spot and futures: pushes are aggregated, so each one carries a version range (`begin` /
`end` on futures, `fromVersion` / `toVersion` on spot), and the documented rule
`version == previous + 1` does not hold. A push with an end below the last applied version is
ignored. A push whose start is at most the last applied version + 1 is applied. Anything else is a
gap. The snapshot's version is on the same counter.

DR-19. A violation of the venue's sequence rule means the local book no longer matches the
exchange's. The book shall be discarded and rebuilt from a new snapshot.

DR-20. A book that is not synced is unknown, not empty. It shall not be classified or shown as live.

DR-21. The best bid shall be lower than the best ask. A crossed or locked local book is a desync and
shall be handled like a sequence gap.

DR-22. Mid price and distance are undefined while either side of the book is empty. No level of such
a book is classified.

DR-23. A snapshot covers a limited number of levels per side (Binance: 100, 500 or 1000). A level
deeper than the snapshot is known only after a diff has touched it since the last sync, so the book
beyond snapshot depth is partial. The depth each snapshot covers, as a distance from mid, shall be
known per instrument, and features that look far from mid shall take it into account.

DR-24. Venues push book updates at different intervals (Binance spot 1 s, Binance futures 500 ms,
MEXC spot 100 ms). Time-based measures such as level lifetime and freshness have the resolution of
their venue's interval and shall not be compared across venues as if they were equal.

---

## 4. Exchange constraints

DR-25. Each exchange's REST rate limits shall never be exceeded. Exceeding them leads to throttling
and then to IP bans (Binance HTTP 429, then 418), which stop snapshot recovery for every instrument
on that exchange.

DR-26. After a throttle or ban response, requests to that exchange shall stop until the period the
exchange states has passed.

DR-27. A REST response is a valid snapshot only if its body says so. MEXC futures signals throttling
inside an HTTP 200 response.

DR-28. Each exchange's WebSocket limits shall be respected: streams or subscriptions per connection
(Binance spot 1024, MEXC spot 30), the rate of subscribe messages, and connections per IP.

DR-29. Exchanges close connections on their own schedule (Binance after 24 h) and during maintenance.
A closed connection is normal operation, not a failure, and every book on it shall be resynced after
the reconnect.

DR-30. Each exchange's heartbeat requirement (WebSocket ping/pong frames or text pings) shall be met,
or the exchange drops the connection.

DR-31. Where measured exchange behaviour contradicts the exchange's documentation, the measurement
wins (the MEXC version rule in DR-18 is one example). A venue's behaviour shall be confirmed
empirically before its adapter is enabled.

DR-32. Each exchange's API terms shall permit commercial redistribution of its market data before
that exchange's data is shown to paying users.

---

## 5. Significant liquidity

DR-33. A level is significant only when its notional is large compared with the normal liquidity of
its instrument. The same notional is a wall on a small cap and noise on BTC.

DR-34. The farther a level is from mid, the larger it must be to matter.

DR-35. A level's significance shall not switch on and off because mid or quantity moves slightly
across a threshold. Small oscillations around a tier boundary are not events.

DR-36. Market makers quote ladders of large levels at a fixed distance from mid and re-quote them as
mid moves. On MEXC futures, 66% of vanished shown levels reappear at a shifted price on the same side
with similar notional (measured 2026-10-07). A level that moves with the price is quoting liquidity,
not a resting wall.

DR-37. A level's identity is (instrument, side, price). Its lifetime starts when that price first
appears in the book. Liquidity that moves to a new price starts a new lifetime.

DR-38. A significant level can disappear in two ways: it is cancelled (pulled) or traded through
(eaten). These mean different things to a trader, and telling them apart requires the trade stream.

DR-39. Layered order clusters and market-maker ladders are the same pattern: several similar-sized
levels close together on one side. Cluster detection and noise filtering shall share one definition
of that pattern.

---

## 6. Trades

DR-40. Every trade has an aggressor side. Binance reports it as `m` (buyer is maker), so `m = true`
is a taker sell.

DR-41. Some trade streams already aggregate fills. Binance `aggTrade` merges the fills of one taker
order at one price into one message. What each venue's trade stream emits (raw fills or aggregated
ones) shall be confirmed before detection thresholds are set.

DR-42. A large resting order is often filled by many takers in a short series of prints at one
price. Executed volume shall be aggregated over such a series, by instrument, price and aggressor
side, before it is judged.

---

## 7. Time, candles and history

DR-43. Exchange event time and local receive time are different clocks. Each time-based value
(level lifetime, candle bucket, alert time) shall use one of them consistently.

DR-44. The server clock shall be synchronised with NTP. Local lifetimes and latencies depend on it.

DR-45. Candle intervals are aligned to UTC boundaries. A candle is final only after its interval
closes, and the live candle may still change.

DR-46. Candle volume is either base volume or quote volume, and the two shall never be mixed.

DR-47. Price and volume spikes are measured against a rolling baseline of the same instrument, so
spike detection needs that instrument's history.

DR-48. Exchanges do not provide historical order-book depth. Depth history exists only if the
screener records it itself.

---

## 8. Payments and commercial

DR-49. Money shall be held as exact decimals, never as floating point. Each currency has a fixed
number of fractional digits (UZS and USD: 2).

DR-50. Multicard exchanges amounts in tiyin (1/100 UZS). Amounts shall be converted to minor units at
the provider boundary, and a callback whose amount does not match the order exactly shall be
rejected.

DR-51. The fiscal receipt requirements for selling a digital subscription in Uzbekistan shall be
confirmed, and fiscalization (Multicard `ofd`) enabled if they apply.

DR-52. When the provider refunds or reverses a payment, the access granted for it shall follow a
stated policy: revoked, kept or prorated.

DR-53. The screener's signals are information, not financial advice. The terms of service and the UI
shall say so.

---

## 9. Out of scope

The screener does not model:

- individual orders within a level (L3 data);
- inverse contracts, delivery futures or options;
- quote currencies other than USDT;
- cross-venue arbitrage or price comparison;
- wash-trading or manipulation detection.
