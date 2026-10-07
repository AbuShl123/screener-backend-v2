# MEXC Market-Maker Liquidity: Detection Options and Roadmap

**Written**: 2026-10-07.
**Status**: research notes and roadmap. Nothing here is implemented.
**Context**: `.claude/plans/mexc-feed-noise.md`. Its 1% per-venue visibility cap
(`max-visible-distance: 0.01` on MEXC futures) did not remove the noise. MEXC books also carry
large market-maker (MM) levels below 1% from mid. The plan assumed the ladders start at about 3%,
and that assumption was wrong.

**Question**: can MM levels be detected and filtered without hiding large orders that are genuine
and interesting to traders? MEXC depth carries only price and quantity.

**Short answer**: no single property proves that a level belongs to an MM. MM orders are real
resting orders. There are strong statistical signs, though, and a better question to ask: does
this level stand out from its own book's normal state? Tag suspect levels; do not drop them.

---

## 1. What makes a level look like MM liquidity

An MM quotes both sides to earn the spread and rebates, and usually hedges on another exchange
(for MEXC, typically Binance). The large MEXC ladders are probably contracted liquidity
providers. Listing and MM agreements commonly require a minimum depth within ±2% of mid (the same
"±2% depth" metric CoinGecko and CMC publish). That fits what was measured: fixed distances,
mirrored sides and oversized notionals. The orders are mostly there to make the book look deep,
not to be filled.

Candidate signs:

1. **Symmetry**: a matching wall on the opposite side at about the same distance and notional.
   The XRP and APT ladders in `mexc-feed-noise.md` §1 show this. One trader's genuine wall is
   almost always one-sided, so this test rarely hides a real order.
2. **Equal-size ladders**: several levels of nearly equal size at evenly spaced distances (for
   example 3.5 / 3.6 / 3.7%). Typical of an algorithm, not of a person.
3. **Size out of proportion to trading**: the median visible MEXC level is 26% of its symbol's 24h
   MEXC turnover, against 0.16% on Binance. A genuine trader does not rest a quarter of a day's
   volume within 1% of mid. `mexc-feed-noise.md` §4 rejected a turnover ceiling mainly because it
   also hit Binance. Applied to MEXC only, that objection is much weaker.
4. **Same pattern across symbols**: the same distance fractions on dozens of tickers point to one
   algorithm.
5. **Pulled when approached**: the order is cancelled or moved away before price reaches it. This
   is the sign traders care about most. It can only be seen together with the trade stream (§3).

**Unverified**: it is not yet known which of these hold on MEXC. One measurement points against
the obvious "MM quotes follow mid" sign: `mexc-feed-noise.md` §4 found that the flickering levels
are *old*. So at least some MM levels stay at a fixed price instead of following mid. Measure
before building (§4, step 2).

## 2. Better framing: compare each level to its own book

A wall matters to a trader because it is unusual, not because of its absolute notional. MM
liquidity is the normal background of a MEXC book, so score each level against that same book:

- **Against other levels now**: the level's notional divided by the median notional of the levels
  on the same side within the cap. In a ladder of equal levels, none stands out. A single genuine
  wall does.
- **Over time**: the same comparison against a rolling per-symbol baseline (for example, depth
  within 1%). Ladders that are always there become the baseline; a new large order stands out.

This targets the real goal (useful signals) instead of an "MM / not MM" label that cannot be
checked.

Risks:
- A genuine wall that sits for hours slowly becomes part of the baseline.
- A genuine order hidden inside a huge ladder is masked. On thin MEXC coins this should be rare,
  and the order is arguably less meaningful there.

### Avoiding false positives: tag, don't drop

Do not drop suspect levels. Tag them with an additive field on the classified level (for example a
boolean `mmLikely` or a score), and let the frontend dim them or hide them behind a toggle:

- Nothing is silently hidden, and the user can still see everything.
- Toggle usage and flagged levels become data for tuning the heuristic.
- Symmetry and ladder shape are the safest first tags: a one-sided order can never trigger them.

The field is additive, so existing clients are unaffected. It still needs a frontend contract
update in `.claude/docs/for-frontend/websocket-feed-api.md`.

## 3. `sub.deal` (executed trades)

Worth building, but as a separate feature. It does not fix the depth noise.

- **Too late for walls**: a wall is valuable before it trades, as likely support or resistance.
  Once it has been executed, it is gone. "A large trade printed" is a useful signal on its own
  (whale prints, tape alerts), but a different one.
- **A large order rarely executes in one deal**: `sub.deal` reports taker trades, so a $2M wall
  usually disappears through many small fills. To say a wall was genuine, add up fills at that
  price.
- **Strongest combined with depth**: when a level's quantity drops, match the drop against trades
  at that price to tell **filled** from **pulled**. This gives traders "absorption" (how much of a
  wall has been eaten) and "pulled" signals, and it is the closest thing to real proof of MM
  spoofing (sign 5 in §1).
- **Caveat**: MEXC is widely suspected of wash trading, and MMs can trade with themselves. A print
  is strong evidence, but not proof.
- **Fit**: a new `FeedChannel` bean; `FeedBroadcaster` does not change. Check MEXC's
  per-connection subscription limits before adding a second topic per symbol. Trade messages are
  far fewer than depth messages.

## 4. Roadmap

1. **Now**: the per-venue default notional scale (`mexc-feed-noise.md` §4, "per-venue notional
   scale"; a replay at ×3 brought MEXC from 2808 to 884 messages per 120s, about Binance's volume)
   plus tier hysteresis (`mexc-feed-noise.md` §3). Both are cheap and easy to undo.
2. **Measure**: record raw MEXC futures depth for 5–10 symbols over about 30 minutes and check
   which signs from §1 actually hold: symmetry, equal-size ladders, whether levels follow mid, and
   how long levels last at one price. `.claude/tools/feed-capture/` records only the classified
   `/ws` feed, so this needs a new raw-depth recording tool.
3. **Tag**: implement the signs that held (symmetry and ladder shape first, then the comparison
   to the book's baseline from §2) as an additive tag on classified levels. Do not filter.
4. **Separately**: `sub.deal` for large-trade alerts and to tell filled walls from pulled ones
   (§3).
