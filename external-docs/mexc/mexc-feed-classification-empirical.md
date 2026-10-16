# MEXC Futures Classified Feed — Empirically Tested

> **Empirically tested** on 2026-10-04 against a local run (Phase 4b code, uncommitted), via
> `ws://localhost:8080/ws` with an ADMIN account that has **no custom rules** (so the global
> default-tier feed), cross-checked against `GET https://api.mexc.com/api/v1/contract/depth/{symbol}?limit=1500`
> and `/api/v1/contract/detail`. Prompted by feed payloads with very large quantities (e.g. DOGE
> `2.234829E8`) and almost only tier 3 / tier 4 levels. Relates to `.claude/plans/mexc-impl-plan.md`
> §1.1 (contract-size scaling) and open decision **D2** (global tier calibration).

## TL;DR

| Question | Result |
|---|---|
| Is `quantityMultiplier` (`contractSize`) applied correctly? | **Yes.** 108 / 108 feed levels across 11 symbols matched the same-moment REST book exactly (`vol × contractSize`), for contract sizes from 0.0001 (BTC) to 10,000,000 (PEPE). |
| Are the huge quantities real? | **Yes.** Quantities are in base asset; 2.2e8 DOGE at ~$0.09 ≈ $20M. |
| Why mostly tier 3 / 4? | **Market-maker ladders.** Nearly every major MEXC pair carries fixed-notional bands at ~±1–2% ($1–2M/level) and ~±3.5–3.7% ($10–50M/level). The default tiers classify them as walls. |
| Is it a code bug? | **No.** It is a tier-calibration question (D2). Pending a decision with the business client. |

## Method

1. Logged in via `POST /api/auth/login`, connected to `/ws?token=…`, recorded 60s of frames
   (2,788 messages: 1 `SNAPSHOT`, 2,591 `UPDATE`, 99 `ADD`, 97 `DROP`; 93 distinct MEXC symbols seen).
2. Rebuilt the final feed state (snapshot + updates − drops) and grouped levels by exchange and tier.
3. Same-moment cross-check: kept the feed state live over WS and, for 11 symbols, fetched the REST
   depth snapshot (1 req per ~0.7s, well under the 4 req/s budget) and compared every feed level
   with the REST row at the same price, scaled by `contractSize`.

A first, non-simultaneous comparison (REST fetched ~1 min after the capture) appeared to show
"phantom" walls at prices where REST had 1–2 contracts. That was a timing artifact: the ladders
re-quote by a tick or two as mid moves (see below). The same-moment check removed every mismatch.

## Scaling check (same-moment, feed vs REST)

All 108 levels: `MATCH` (feed quantity = REST `vol × contractSize`, within 2%; in practice exact).

| Symbol | contractSize | Feed quantity | REST | Notional | Tier | Distance |
|---|---|---|---|---|---|---|
| DOGE_USDT | 100 | 3.29852e8 | 3,298,520 contracts | $30,000,003 | 4 | 3.46% |
| XRP_USDT | 1 | 3.41817e7 | same | $49,519,061 | 4 | 3.51% |
| PEPE_USDT | 1e7 | 5.86067e12 | same | $24,280,170 | 4 | 3.50% |
| APT_USDT | 0.1 | 1.96798e7 | same | $15,411,280 | 4 | 3.18% |
| SUI_USDT | 1 | 1.00688e7 | same | $12,038,236 | 4 | 3.51% |
| BTC_USDT | 0.0001 | 61.8569 | same | $5,255,208 | 1 | 0.22% |
| ETH_USDT | 0.01 | 1276.19 | same | $3,432,913 | 1 | 0.19% |
| LINK_USDT | 0.1 | 127,977 | same | $1,796,929 | 3 | 0.57% |
| ZEC_USDT | 0.01 | 762.44 | same | $996,456 | 2 | 0.82% |
| HYPE_USDT | 0.1 | 7065.3 | same | $628,035 | 2 | 0.94% |
| ADA_USDT | 1 | 3.563e6 | same | $876,497 | 2 | 0.43% |

(One row per symbol shown; SOL_USDT had no level passing the default tiers at the time.)

BTC wall notional on MEXC (~$5M, tier 1 under the high-liquidity rules) is the same order of
magnitude as Binance — the Phase 5 sanity check for §1.1 holds.

## Market-maker ladders

Shape, consistent across DOGE, XRP, PEPE, APT, SUI, LTC, ARB, SHIB, …:

- **Outer band**: 3 levels per side at ~±3.5% / ±3.6% / ±3.7% from mid, each **$10–50M**, with
  round notional targets ($30,000,003; $24,280,170 on both sides of PEPE; $13,660,490 on both sides
  of APT). Symmetric around mid.
- **Inner band**: levels at ~±1–2%, each **~$1.5–2M** (also round: ~$1.5M DOGE/SUI, ~$2M PEPE/APT).
- **Re-quoted continuously**: over the 60s capture, APT showed 46 distinct tier-4
  `(side, price, firstSeenMillis)` combinations from ~6 ladder slots (≈ each slot moved every ~8s);
  PEPE 42, DOGE 33, SUI 26, XRP 24.

Default tiers (`GET /api/rules/default`, normal symbols):

| Tier | minNotional | maxDistance |
|---|---|---|
| 4 | $10,000,000 | 5% |
| 3 | $1,000,000 | 2% |
| 2 | $500,000 | 1% |
| 1 | $200,000 | 0.5% |

So the outer band always lands in tier 4 and the inner band in tier 3. Because top-5 per side is
ranked `tier DESC, notional DESC, distance ASC`, these bands fill the top slots for most major MEXC
pairs and push genuine walls out of view.

For comparison, Binance futures at the same moment: largest DOGE wall ~$1.44M, largest XRP ~$1.07M;
no Binance level in the capture reached tier 4.

Distribution over the final 60s state:

| | Symbols in feed | Levels | Tier 1 | Tier 2 | Tier 3 | Tier 4 | Median notional |
|---|---|---|---|---|---|---|---|
| Binance | 21 | 68 | 40 | 15 | 13 | 0 | ~$388K |
| MEXC | 64 | 381 | 83 | 113 | 143 | 42 | ~$1.07M |

## Options (undecided — pending business client)

1. **Per-exchange default tiers**: e.g. a MEXC tier-4 `maxDistance` below ~3%, or higher MEXC
   notional thresholds. Cheapest; fits the existing rule model.
2. **Lifetime filter** on `firstSeenMillis` (reject levels younger than N seconds): catches
   re-quoting ladders on any venue, but must not suppress genuinely new walls.

Recommendation: start with (1).
