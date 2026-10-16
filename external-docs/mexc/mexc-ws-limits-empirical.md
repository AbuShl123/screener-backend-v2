# MEXC WebSocket Limits — Empirically Tested

> **Empirically tested** on 2026-10-01 (~20:25–20:45 UTC) from a single residential IP, against
> spot `wss://wbs-api.mexc.com/ws` (channel `spot@public.aggre.depth.v3.api.pb@100ms@<SYMBOL>`) and
> futures `wss://contract.mexc.com/edge` (`sub.depth`). Universe at test time: **1497 spot USDT
> pairs**, **1106 futures `_USDT` contracts**. Treat it as one IP, one time window. It complements
> `mexc-api-contracts.md` (docs-derived) and `mexc-api-rate-limits-empirical.md` (REST).

## TL;DR

| | Spot | Futures |
|---|---|---|
| Subscriptions per connection | **30, hard-enforced** | **No limit observed**: all 1106 on one connection |
| Simultaneous connections per IP | **No cap observed up to 300** | **No cap observed up to 150** |
| Connection-open burst | 100 opened at once, all OK | 100 opened at once, all OK |
| Connections needed for the full USDT universe | **~50** (1497 / 30) | **1** (or a handful for headroom) |

## Spot

### Subscriptions per connection

| Run | Outcome |
|---|---|
| 30 channels in one `SUBSCRIPTION` | acked, 30/30 deliver data |
| 31 channels in one `SUBSCRIPTION` | first 30 accepted, the 31st rejected; 30/31 deliver data |
| 60 channels sent as 6 messages × 10 | first 3 messages accepted, last 3 fully rejected; 30/60 deliver data |

**The 30-per-connection limit is real**, counted cumulatively per connection. Splitting the
subscriptions across several messages does not get around it.

**Gotcha: a rejection is not an error code.** The reply is still `"code":0`. Only the `msg` text
shows it:

```json
{"id":0,"code":0,"msg":"Subscribed successful! [<ch>,...]. Not Subscribed successfully! [<ch>].  Reason： Exceeded maximum subscription limit! "}
```

(A fully successful subscribe replies with `msg` = the plain comma-joined channel list, without the
"Subscribed successful!" prefix.) The adapter must parse `msg` for `Not Subscribed successfully! [`
followed by a non-empty list, or it will silently miss books. Note the full-width colon `：` in
`Reason：`.

### Simultaneous connections

| Run | Outcome |
|---|---|
| 50 conns × 30 subs (whole universe, 1497 symbols), 100ms stagger, 75s | 50/50 alive, all delivering, ~3.2k msg/s total |
| 150 conns × 1 sub, 50ms stagger, 60s | 150/150 alive, all delivering |
| 300 conns × 1 sub, 50ms stagger, 60s | 300/300 alive, 299 delivering (1 illiquid symbol) |
| 100 conns × 1 sub, **all opened at once**, 40s | 100/100 alive, all delivering |

No connection cap and no handshake throttling were hit up to 300 connections. The full spot universe
fits in ~50 connections, so 300 leaves 6× headroom. Higher counts were not tested; they are not needed.

## Futures

### Subscriptions per connection

| Run | Outcome |
|---|---|
| 50 `sub.depth` on one connection | 50 acked, 50/50 deliver |
| 300 on one connection | 300 acked, 300/300 deliver |
| **1106 (entire universe) on one connection**, 30s / 45s / 180s runs | **1106 acked**, 1092 deliver; stable for 3 min with no disconnect, ~1.1k msg/s |

- **No per-connection subscription limit was found.** All 1106 `sub.depth` were sent back to back
  in ~0.6s, with no pacing and no rejection.
- The 14 silent contracts were always the 14 with the lowest 24h volume (tokenized stocks like
  `KBLAMSTOCK_USDT` and dead pairs like `WBTC_USDT`, `STETH_USDT`, `TON_USDT`, `USD1_USDT`). They had no
  book changes, so this is not a limit.
- The ack is `{"channel":"rs.sub.depth","data":"success","ts":...}` and **does not echo the symbol**,
  so acks can only be matched to requests by order/count.

### Simultaneous connections

| Run | Outcome |
|---|---|
| 150 conns × 1 sub, 50ms stagger, 60s | 150/150 alive, all delivering |
| 100 conns × 1 sub, all opened at once, 40s | 100/100 alive, all delivering |

## Implications for the adapter

- **Spot**: cap streams per connection at **30**. With `ConnectionPool`, this is just the venue's
  per-connection stream cap in `screener.exchanges.mexc.venues.spot.*`. Expect ~50 connections for
  the full USDT universe. Treat `Not Subscribed successfully!` in an ack as a failure, even though
  `code` is 0.
- **Futures**: one connection can carry the whole universe. Still use a moderate cap (e.g. a few
  hundred per connection) so one dropped socket does not resync every futures book at once and
  reconnect traffic stays reasonable. That is a design choice; the exchange does not require it.
- Opening connections quickly is not punished on either venue at these scales. The existing
  pool start-up needs no special connect throttling.

## Not tested

- Spot connection counts above 300, futures above 150.
- Long-run behaviour (the documented 24h spot connection lifetime, behaviour over hours).
- Whether the spot limit is the same for the `10ms` depth variant or other channel types. The docs
  say it is per connection regardless of channel.
- Subscribe-message rate limits on spot. Futures took 1106 subscribe messages in ~0.6s without complaint.
