# MEXC API Rate Limits — Empirically Tested

> **Empirically tested** on 2026-10-01 (~19:10–19:50 UTC), with a futures follow-up on 2026-10-04
> (see "Futures — V2 follow-up"), from a single residential IP, against
> spot `GET https://api.mexc.com/api/v3/depth?symbol=BTCUSDT&limit=100` and futures
> `GET https://contract.mexc.com/api/v1/contract/depth/BTC_USDT`. Results reflect one IP, one
> region's Akamai edges, and one time window — treat thresholds as approximate. Compare with the
> documentation-derived claims in `mexc-api-rate-limits.md`.

## Spot — `GET /api/v3/depth`

### Runs

| Run | Requests | Outcome |
|---|---|---|
| 8 req/s × 20s | 160 | all 200 |
| 12 req/s × ~20s | ~240 | all 200, no 429 |
| 20 req/s × 20s | 400 | 395 × 200, 5 × 429 (sporadic, around t≈11s) |
| 20 req/s × ~28s (repeat) | ~570 | all 200 |
| Burst ~57 req/s (pre-warmed connections) | 150 | 127 × 200 within 1.4s, then 403 |
| Burst ~32 req/s (pre-warmed), ×2 | 250 each | ~130 × 200, then 403 |
| 30 req/s × 30s | 900 | 616 × 200, 284 × 403 in repeated waves |

### Findings

1. **The documented "100 req / 10s" (weight 3, 300 / 10s) is not what is enforced.** 20 req/s held
   for ~28s (2× the documented budget) and 127 requests in 1.4s passed without a single 429.
2. **The binding limit is Akamai's WAF in front of MEXC, not MEXC's own limiter.** It fires
   before the origin 429 does.
   - Response: **HTTP 403**, HTML body `<TITLE>Access Denied</TITLE>`, `Content-Type: text/html`,
     `Connection: close`, `Akamai-GRN` header. **No `Retry-After`.**
   - Trigger: somewhere between **20 and 30 req/s sustained**, or **~120–130 requests in a 1–2s burst**.
   - Scope: **per IP, host-wide** — `/api/v3/time` is also 403'd during a block.
   - Detection is **delayed**: a run can finish with all 200s and the block appears a few seconds later.
   - Some 403s are tarpitted (response took up to ~8s).
3. **MEXC origin 429 exists but is rare.** Body is JSON `{"code":429,"msg":"Too Many Requests"}`,
   **no `Retry-After` header.** Seen only 5 times, at 20 req/s.
4. **No 418 observed, no escalating ban observed.** The WAF block is "flappy" rather than a fixed
   ban: for **~20–100s** after a trigger, responses alternate 403 ↔ 200 (consistent with independent
   Akamai edge nodes converging on block state). Triggering twice back-to-back did not lengthen it.
   The documented 2 min → 3 day escalation was **not tested** (sustained hammering through 403s
   was deliberately avoided).

### Not tested (spot)

- Whether `limit=5000` costs more than `limit=100` (response is ~104 KB vs a few KB).
- The 418 / long-ban escalation path.

## Futures — `GET /api/v1/contract/depth/{symbol}`

> **Empirically tested** on 2026-10-01 (~19:45–19:50 UTC), same IP, against
> `GET https://contract.mexc.com/api/v1/contract/depth/BTC_USDT`. Deliberately gentle: every run
> stopped at the first rejection.

### Runs

| Run | Requests | Outcome |
|---|---|---|
| 4 req/s × 20s | 80 | all OK |
| 5 req/s × 20s (exactly the documented limit) | 24 | 23 OK, then rejected at t≈4.6s |
| 8 req/s (stop at first rejection), ×2 | 16 / 21 | rejected after 13 OK (t≈1.6s) and 19 OK (t≈2.4s) |

### Findings

1. **The documented 10 requests / 2s (5 req/s) roughly holds.** Rejections came after ~13–19 OKs
   at 8 req/s, consistent with a ~10-per-2s window (fixed-window boundary effects allow a little over).
   Running *at* exactly 5 req/s still trips it occasionally because network jitter bunches arrivals.
2. **The rejection is not an HTTP error.** It is **HTTP 200** with JSON body
   `{"success":false,"code":510,"message":"Requests are too frequent, please try again later"}`.
   No `Retry-After`, no rate-limit headers. **A client must check `success`/`code`, not the status code.**
3. **No penalty.** A poll ~0.5s after a rejection already succeeded — it is a plain counting window,
   not a ban. No 403/429/418 observed on futures at these rates.
4. No rate-limit headers on successful responses either (no used-weight equivalent to Binance's).
   The host is also behind Akamai (`Server-Timing: cdn-cache`, `ak_p`), so the spot-style WAF 403 may
   exist here too at higher rates — not tested.

### Not tested in the first session (V2 below covers the last two)

- Higher rates / sustained violation (whether the Akamai WAF 403 also applies here).
- Whether the window is per IP or per symbol, and whether it shares state with `api.mexc.com`.
- Effect of the `limit` query parameter.

## Futures — V2 follow-up (host, scope, `limit`, discovery)

> **Empirically tested** on 2026-10-04 (~15:17–15:24 UTC), same residential IP, mostly against
> `GET https://api.mexc.com/api/v1/contract/depth/{symbol}` (the configured `rest.base-url`).
> Probe: Python `requests`, keep-alive session per worker thread, sends scheduled at a fixed
> cadence, every run stops at the first rejection, ≥5–15s pause between runs. No 403/429 seen in
> any run; every rejection was the HTTP 200 `code:510` body. Plan: `.claude/plans/mexc-impl-plan.md`
> Phase 4b, V2a–V2d.

### Runs

| Check | Run | Sent | Outcome |
|---|---|---|---|
| V2a | `api.mexc.com`, BTC_USDT, no `limit`, 4 req/s × 20s | 80 | all OK |
| V2a | same, 5 req/s × 20s | 100 | all OK |
| V2a | same, 5 req/s × 20s (repeat) | 18 | 16 OK, then 510 at t≈3.2s (that run's latency p50 was 1.2s — bunched arrivals) |
| V2a | **both hosts at once**, 4 req/s each (8 combined), BTC_USDT | 9 + 10 | `api` 8 OK → 510 at t≈2.0s; `contract` 9 OK → 510 at t≈2.25s |
| V2b | 8 req/s rotating 40 distinct symbols | 15 | 510 at t≈0.9s after 7 OK; 12 OK total |
| V2b | 8 req/s rotating 24 distinct symbols (after a 15s pause) | 23 | 20 OK, then 510 at t≈2.5s |
| V2c | BTC_USDT `limit=20`, 8 req/s × 10s | 80 | **all OK** |
| V2c | BTC_USDT `limit=20`, 5 req/s × 20s | 100 | all OK |
| V2c | BTC_USDT `limit=100`, 8 req/s | 19 | 16 OK, then 510 at t≈2.0s |
| V2c | BTC_USDT `limit=500`, 8 req/s | 24 | 21 OK, then 510 at t≈2.6s |
| V2c | BTC_USDT `limit=1000`, 5 req/s | 15 | 12 OK, then 510 at t≈2.4s |
| V2c | BTC_USDT no `limit` (1500 levels), 4 req/s × 20s | 80 | all OK |
| V2d | depth 4 req/s × 10s **and** `/api/v1/contract/detail` 2 req/s × 10s, concurrently | 40 + 20 | all OK (6 req/s combined) |

### What `limit` returns

| Symbol | `limit` | Levels (asks / bids) | Body | Reach from mid (ask / bid) |
|---|---|---|---|---|
| BTC_USDT | none | 1500 / 1500 | 43 KB | 0.85% / 0.99% |
| BTC_USDT | 1000 | 1000 / 1000 | 29 KB | 0.54% / 0.70% |
| BTC_USDT | 1500, 2000 | 1500 / 1500 | 43 KB | same as none |
| ETH_USDT | none | 1500 / 1500 | 43 KB | 5.0% / 4.5% |
| ETH_USDT | 1000 | 1000 / 1000 | 29 KB | 3.0% / 2.7% |
| DOGE_USDT | none | 974 / 1303 | 33 KB | 908% / 99.99% (book exhausted — stale far orders) |
| DOGE_USDT | 1000 | 973 / 1000 | 29 KB | 908% / 29% |

### Findings

1. **V2a — the two hosts are one limiter.** `api.mexc.com` behaves exactly like `contract.mexc.com`
   (4 req/s clean; 5 req/s sometimes clean, sometimes trips after ~16), and splitting 4 + 4 req/s
   across the two hosts tripped both within ~2s — the same as 8 req/s on one host. Same Akamai front
   (`Akamai-GRN` header on `api`), same origin. Switching hosts buys nothing, and neither does
   spreading traffic over both. `api.mexc.com` stays the base URL.
2. **V2b — the window is per IP (or per client), not per symbol.** Rotating across 24–40 distinct
   symbols at 8 req/s tripped after 7–20 OKs — the same band as single-symbol 8 req/s (13–19 OKs on
   2026-10-01). Consistent with one ~10-per-2s window per IP; the spread (7 vs 20) is fixed-window
   boundary luck plus arrival jitter.
3. **V2c — for any useful depth, `limit` does not change the cost.** `limit` 100, 500, 1000 and 1500
   all trip in the same place (~10 per 2s). Only `limit=20` escaped the limiter: 80 requests at 8 req/s
   and 100 at 5 req/s with no rejection (the threshold lies somewhere in 21–100; not narrowed further —
   20 levels is useless as a snapshot). So the deepest snapshot is free: **1500 levels per side is the
   server cap** (`limit=2000` and no `limit` both return 1500), and it is the same price as 1000.
   Even 1500 levels reach under 1% from mid on BTC_USDT — far inside the 10% price filter — so on
   liquid contracts depth, not the filter, bounds the seeded book.
4. **V2d — discovery does not share the depth budget.** 2 req/s of `/api/v1/contract/detail` (2.3 MB
   each) alongside 4 req/s of depth (6 req/s combined, above the ~5 req/s depth limit) produced no
   rejection on either. Detail has its own limiter (or none at that rate); a refresh-time detail call
   cannot cause a snapshot 510.
5. Still not tested: sustained violation / whether the Akamai 403 exists on the futures path; the
   `depth_commits` endpoint's limit.

## Implications for the snapshot fetcher

- Budget spot snapshots at **~10 req/s** (≈2× headroom under the lowest rate that produced any rejection).
- Space requests evenly; the WAF reacts to bursts.
- Treat **HTML 403** as a rate-limit signal, not an auth error.
- On **403 or 429**, pause **all** REST traffic for the venue for **≥60–90s** — there is no
  `Retry-After` to rely on, and quick retries hit edges that are still blocking.
- Budget futures snapshots at **~4 req/s** (4 req/s was clean; 5 req/s occasionally trips). The
  budget is per IP across both futures hosts and all symbols (V2a/V2b), so it is a single global
  pace for the whole venue; discovery's `/contract/detail` does not draw from it (V2d).
- Request the full **1500 levels** (`limit=1500`): it costs the same as any `limit` ≥ 100 (V2c).
- On futures, detect throttling via **`success:false` / `code:510` inside an HTTP 200**; a short
  back-off (~2s, one window) is enough, since there is no lingering penalty.
