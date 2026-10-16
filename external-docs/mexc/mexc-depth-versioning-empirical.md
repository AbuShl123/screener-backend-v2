# MEXC Futures Depth Versioning — Empirically Tested (V1)

> **Empirically tested** on 2026-10-03 from a single residential IP, against futures
> `wss://contract.mexc.com/edge` (`sub.depth`) and `GET https://api.mexc.com/api/v1/contract/depth/{symbol}?limit=1000`.
> Task V1 of `.claude/plans/mexc-impl-plan.md` (Phase 0). It complements the docs-derived
> `mexc-api-contracts.md` §3.2, and **overrides it where they disagree**.

## TL;DR

| Question | Docs say | Measured |
|---|---|---|
| (a) push `version` increments by exactly 1 | yes | **No.** Pushes are **aggregated**: `version` jumps by 1 to several hundred between consecutive pushes. |
| Is the stream still gap-checkable? | — | **Yes, via undocumented `begin` / `end` fields.** Every push carries the version range it covers; `begin == previous end + 1` held on **every** push (22,706 pushes, 0 gaps, 0 overlaps). `version == end` always. |
| (b) snapshot `version` on the push counter | yes | **Yes.** Snapshot + subsequent pushes reproduces a later snapshot exactly (top 50 levels, both sides). |
| Snapshot alignment to push boundaries | — | **Not aligned.** 6 of 21 snapshots had a `version` strictly *inside* a push's `[begin, end]` range. |
| (c) `vol` is in contracts | yes | **Yes.** BTC_USDT (`contractSize` 0.0001) top-of-book rows of 49,886 / 276,746 = 5 / 28 BTC; read as BTC they would be $4.2B / $23B. |

## Push frame, as actually delivered

```json
{"symbol":"PLTRSTOCK_USDT",
 "data":{"cts":1791040915686,"asks":[[189.1,4640,2]],"bids":[],"end":1205403582,"begin":1205403582,"version":1205403582},
 "channel":"push.depth","ts":1791040915689}
```

- Top-level order is `symbol`, `data`, **`channel` last**, `ts`. The docs show `channel` first.
  Control frames (`rs.sub.depth`, `pong`) do have `channel` first:
  `{"channel":"rs.sub.depth","data":"success","ts":…}`, `{"channel":"pong","data":<ms>,"ts":<ms>}`.
- Inside `data`: `cts`, `asks`, `bids`, then `end`, `begin`, `version` — **all sequence fields
  come after the levels** (confirms plan §1.2, now for three fields rather than one).
- Levels are JSON numbers `[price, vol, orderCount]`; `vol == 0` removes the level.
- `begin`/`end` were present on 100% of pushes. They do not appear in MEXC's docs.
- The first push after subscribing can cover a wide range (BTC_USDT: 369 versions, 151 levels) —
  it is just an aggregated update, not a snapshot, and it is contiguous with what follows.

Snapshot body: `{"success":true,"code":0,"data":{"cts":null,"asks":[…],"bids":[…],"version":…,"timestamp":…}}`,
1000 rows per side at `limit=1000`, same `[price, vol, orderCount]` triplets.

## Runs

| Run | Scope | Result |
|---|---|---|
| A — 75s, 7 contracts, 3 snapshot rounds (21 snapshots) | BTC, ETH, ZEC (top 3 by `amount24`), DASH (#50), PLTRSTOCK (#200), MUSEBOOK (#500), XLE (#800) | 1,051 pushes. `version` delta histogram e.g. BTC: 77× `+1`, 81× `+2`, … up to `+690`; MUSEBOOK: 119× `+1`, 109× `+93`. `begin == prev.end + 1` on every push. |
| B — 180s, 82 contracts, no snapshots | top 40 by `amount24` + every 25th of the rest | 21,655 pushes, all 82 delivering. **0 gaps, 0 overlaps, 0 `end != version`, 0 missing `begin`/`end`.** |

### Snapshot alignment (run A)

For each snapshot with version `V`, the push containing `V + 1`:

- **15 / 21** started exactly at `V + 1` (`begin == V + 1`) — the snapshot sits on a push boundary.
- **6 / 21** straddled it: `begin <= V < end`, e.g. ZEC_USDT snapshot `8903933980`, next push
  `[8903933911, 8903933982]`. The snapshot is cut mid-aggregation.

### Reconstruction (run A)

`snapshot#0` + every push with `end > V0`, applied with absolute-quantity upsert/delete, compared with
`snapshot#2` on its top 50 levels per side: **exact match for all 7 contracts**, including the two
whose `snapshot#0` straddled a push (BTC, ZEC). The straddling push re-applies commits already in the
snapshot, which is harmless because quantities are absolute and the push is newer — the same argument
as Binance spot (progress doc §4.5).

(The two `snapshot#0 → snapshot#1` comparisons that mismatched are an artefact of the check, not of
MEXC: `snapshot#1` itself straddled a push, and the harness only applied pushes with `end <= V1`, so it
was missing the commits between that push's `begin` and `V1`.)

## Implications for the sync strategy

The documented `version == lastVersion + 1` rule (plan §3 Phase 3 `check()`) **would desync on most
pushes** — on BTC_USDT ~75% of consecutive pushes jump by more than 1. It must not be used.

The stream is instead exactly Binance-spot-shaped, with `begin` ≙ `U` and `end` ≙ `u`:

```
end   <  lastVersion        → IGNORE      (fully covered by what we have)
begin <= lastVersion + 1    → OK, lastVersion = end
otherwise                   → DE_SYNCED
```

One predicate covers both the post-snapshot sync point (including straddling snapshots) and steady
state. Whether `end == lastVersion` should be `IGNORE` or `OK` is the same question as the Binance
spot `<` vs `<=` decision (decision log #10); re-applying it is harmless either way.

All three sequence fields sit after `asks`/`bids`, so the plan's §1.2 string-scan approach still
applies, now locating `"begin":` and `"end":` (or `"version":`) before the streaming pass.

## Not tested

- Behaviour across a reconnect (does the first push after resubscribing start a fresh range, or
  continue?) — irrelevant to correctness, since a reconnect forces a resync anyway.
- Whether MEXC ever sends `begin`/`end` with `begin > prev.end + 1` under load (a real gap). None
  observed in 22.7k pushes.
- Long runs (hours). `depth_commits` backfill semantics.
