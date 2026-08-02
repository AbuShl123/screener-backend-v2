#!/usr/bin/env python3
"""
Analyse an orderbook monitoring CSV capture.

Consumes `books-*.csv`, `fleet-*.csv` and `shards-*.csv` written by
`monitoring/OrderBookCsvRecorder` and answers the questions listed in
`.claude/docs/orderbook-monitoring-findings.md` §12 — the ones that a point-in-time
sample of `/api/monitoring/orderbook` structurally cannot answer:

  1. book growth / band saturation      6. volatility headroom
  2. bytes per level (live-set slope)   7. bid/ask level tilt
  3. shard imbalance stability          8. fleet composition churn
  4. idle distribution -> stale-threshold-ms
  5. resync behaviour

Reads the CSV column semantics from `.claude/docs/orderbook-monitoring.md` §8 and
honours §8.5 throughout: counter diffs are masked across restarts, gaps are reported
rather than filled, and the first hour is dropped before anything is fitted.

Usage:
    python tools/analyze_orderbook_csv.py [--dir monitoring-data/monitoring-data]
                                          [--out monitoring-analysis]
                                          [--warmup-hours 1.0] [--no-plots]
"""

from __future__ import annotations

import argparse
import os
import sys
import textwrap

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import obcsv  # noqa: E402

# dataviz reference palette, categorical slots 1-3 (light mode).
# Validated: all-pairs CVD dE 9.2, normal-vision 24.0. Aqua carries a sub-3:1
# contrast WARN, so every series is direct-labelled as well as legended.
C_BLUE, C_ORANGE, C_AQUA = "#2a78d6", "#eb6834", "#1baf7a"
INK, INK2, MUTED, GRID = "#0b0b0b", "#52514e", "#8a8985", "#e6e5e1"
SURFACE = "#fcfcfb"

SECTIONS: list[str] = []


def emit(text: str = "") -> None:
    print(text)
    SECTIONS.append(text)


def h1(title: str) -> None:
    emit()
    emit("=" * 78)
    emit(title)
    emit("=" * 78)


# --------------------------------------------------------------------------- #
# 0. capture integrity
# --------------------------------------------------------------------------- #
def capture_integrity(books, fleet, shards) -> None:
    h1("0. CAPTURE INTEGRITY")
    for name, df, cadence in [("books", books, 60), ("fleet", fleet, 30), ("shards", shards, 30)]:
        ticks = df["at"].nunique()
        span = (df["at"].max() - df["at"].min()).total_seconds() / 3600
        gaps = obcsv.tick_grid(df, cadence)
        emit(f"{name:>7}: {len(df):>8,} rows  {ticks:>5} ticks  {span:5.2f} h span  "
             f"{len(gaps)} gaps > {cadence * 1.5:.0f}s")
        if len(gaps):
            emit(textwrap.indent(gaps.to_string(index=False), " " * 9))

    restarts = obcsv.detect_restarts(books)
    emit(f"\nJVM restarts (cumulative counter stepped back): {len(restarts)}")
    emit(f"window: {books['at'].min()} -> {books['at'].max()}")

    per_tick = books.groupby("at").size()
    emit(f"books per tick: min {per_tick.min()} / median {per_tick.median():.0f} / max {per_tick.max()}")
    emit(f"distinct (symbol, market) keys over the whole capture: "
         f"{books.groupby(['symbol', 'market']).ngroups}")

    # composition churn: does any book appear or disappear mid-capture?
    seen = books.groupby(["symbol", "market"])["at"].agg(["min", "max", "size"])
    full = seen["size"].max()
    partial = seen[seen["size"] < full]
    emit(f"books present for every tick: {(seen['size'] == full).sum()} of {len(seen)}")
    if len(partial):
        emit("books with partial presence (ticker churn or late registration):")
        emit(textwrap.indent(partial.sort_values("size").head(15).to_string(), "  "))


# --------------------------------------------------------------------------- #
# 1. growth / saturation
# --------------------------------------------------------------------------- #
def growth(books, fleet, warmup: float):
    h1("1. BOOK GROWTH — is the +-10% band saturating?")
    f = fleet.copy()
    f["h"] = obcsv.hours_since_start(f["at"])
    w = f[f.h > 0.5]
    x, y = w.h.values, w.totalLevels.values.astype(float)

    emit("piecewise linear slope of fleet totalLevels, by fit start:")
    for lo in (1, 2, 4, 6, 8):
        sub = f[f.h > lo]
        slope = np.polyfit(sub.h, sub.totalLevels, 1)[0]
        emit(f"  from h>{lo:>2}: {slope:+9,.0f} levels/h   "
             f"mean {sub.totalLevels.mean():>9,.0f}   last {sub.totalLevels.iloc[-1]:>9,.0f}")

    fit = None
    try:
        from scipy.optimize import curve_fit

        def sat(t, linf, a, tau):
            return linf - a * np.exp(-t / tau)

        p, _ = curve_fit(sat, x, y, p0=[y.max() * 1.05, 1e5, 3.0], maxfev=40000)
        linf, a, tau = p
        rmse_sat = np.sqrt(((y - sat(x, *p)) ** 2).mean())
        rmse_lin = np.sqrt(((y - np.polyval(np.polyfit(x, y, 1), x)) ** 2).mean())
        emit(f"\nsaturating fit L(t) = L_inf - A*exp(-t/tau):")
        emit(f"  L_inf {linf:,.0f} levels   A {a:,.0f}   tau {tau:.2f} h")
        emit(f"  RMSE {rmse_sat:,.0f} vs linear {rmse_lin:,.0f}  "
             f"({'saturating wins' if rmse_sat < rmse_lin else 'LINEAR WINS — still growing'})")
        emit(f"  observed at end {y[-1]:,.0f}; remaining to asymptote "
             f"{linf - y[-1]:,.0f} (+{(linf / y[-1] - 1) * 100:.1f}%)")
        emit("  projection: " + "  ".join(f"t={t}h {sat(t, *p):,.0f}" for t in (12, 24, 48, 72)))
        fit = (sat, p)
    except ImportError:
        emit("\n(scipy unavailable — saturating fit skipped)")

    b = books[books.state == "SYNCED"].copy()
    b["h"] = obcsv.hours_since_start(b["at"])
    early = b[(b.h > warmup) & (b.h < warmup + 1)].groupby(["symbol", "market"])["size"].mean()
    late = b[b.h > b.h.max() - 1].groupby(["symbol", "market"])["size"].mean()
    j = pd.concat([early.rename("early"), late.rename("late")], axis=1).dropna()
    j["growth"] = j.late - j.early
    j["pct"] = j.growth / j.early * 100

    emit(f"\nper-book growth, hour {warmup:.0f}-{warmup + 1:.0f} mean vs final hour mean (n={len(j)}):")
    emit(f"  fleet total {j.early.sum():,.0f} -> {j.late.sum():,.0f} "
         f"({j.growth.sum():+,.0f}, {j.growth.sum() / j.early.sum() * 100:+.1f}%)")
    emit("  per-book % growth: " + "  ".join(
        f"p{int(q * 100)}={j.pct.quantile(q):+.1f}%" for q in (.1, .25, .5, .75, .9, .99)))
    emit(f"  books that shrank: {(j.growth < 0).sum()} of {len(j)}")
    top = j.sort_values("growth", ascending=False)
    emit(f"  share of ALL fleet growth from the 10 biggest growers: "
         f"{top.head(10).growth.sum() / j.growth.sum() * 100:.1f}%")
    emit(textwrap.indent(top.head(10).round(1).to_string(), "    "))

    # residual growth in the final window
    tail = b[b.h > b.h.max() - 2]
    slopes = tail.groupby(["symbol", "market"]).apply(
        lambda d: np.polyfit(d.h, d["size"], 1)[0] if len(d) > 2 else np.nan,
        include_groups=False).dropna()
    emit(f"\nfinal-2h per-book slope: median {slopes.median():+.1f} levels/h, "
         f"sum {slopes.sum():+,.0f} levels/h")
    emit(f"  growing >1/h: {(slopes > 1).sum()}   flat: {(slopes.abs() <= 1).sum()}   "
         f"shrinking <-1/h: {(slopes < -1).sum()}")
    return j, fit


# --------------------------------------------------------------------------- #
# 2. bytes per level
# --------------------------------------------------------------------------- #
def memory(fleet, warmup: float):
    h1("2. MEMORY — bytes per level from the heap lower envelope")
    f = fleet.set_index("at").copy()
    f["h"] = obcsv.hours_since_start(fleet["at"]).values
    w = f[f.h > warmup]
    xmx = w.maxHeapBytes.iloc[0]

    emit(f"maxHeapBytes (-Xmx) {xmx / 2**30:.2f} GiB")
    emit(f"usedHeapBytes post-warmup: min {w.usedHeapBytes.min() / 2**20:.0f} MiB  "
         f"median {w.usedHeapBytes.median() / 2**20:.0f} MiB  "
         f"max {w.usedHeapBytes.max() / 2**20:.0f} MiB  "
         f"(peak = {w.usedHeapBytes.max() / xmx * 100:.1f}% of Xmx)")
    emit(f"GC sawtooth amplitude (p95-p05): "
         f"{(w.usedHeapBytes.quantile(.95) - w.usedHeapBytes.quantile(.05)) / 2**20:.0f} MiB")
    emit("\nnaive single-reading estimates (what a point-in-time sample gives you):")
    emit(f"  mean heap / mean levels = {w.usedHeapBytes.mean() / w.totalLevels.mean():.0f} B/level")
    emit(f"  min  heap / mean levels = {w.usedHeapBytes.min() / w.totalLevels.mean():.0f} B/level")

    emit("\nlower-envelope regression  usedHeap_min ~ a + b * totalLevels:")
    best = None
    for win in ("5min", "10min", "20min", "30min"):
        live = obcsv.lower_envelope(w.usedHeapBytes, win)
        m = pd.concat([live.rename("live"), w.totalLevels.rename("lv")], axis=1).dropna()
        slope, icept = np.polyfit(m.lv, m.live, 1)
        r = np.corrcoef(m.lv, m.live)[0, 1]
        emit(f"  window {win:>6}: {slope:7.1f} B/level   baseline {icept / 2**20:6.1f} MiB   r {r:+.3f}")
        if win == "10min":
            best = (slope, icept, m)

    slope, icept, m = best
    rng = np.random.default_rng(0)
    boots = np.array([np.polyfit(*(lambda i: (m.lv.values[i], m.live.values[i]))(
        rng.integers(0, len(m), len(m))), 1)[0] for _ in range(2000)])
    lo, hi = np.percentile(boots, [2.5, 97.5])
    emit(f"\n-> live-set model:  heap = {icept / 2**20:.0f} MiB + {slope:.0f} B/level")
    emit(f"   bootstrap 95% CI on the slope: [{lo:.0f}, {hi:.0f}] B/level (2000 resamples)")
    emit(f"   CAVEAT: totalLevels only spans {m.lv.min():,.0f}-{m.lv.max():,.0f} "
         f"(a {m.lv.max() / m.lv.min() - 1:.0%} range), so the intercept is a long extrapolation")
    emit(f"   and {slope:.0f} B/level is the MARGINAL cost of a level, not its total attribution.")
    emit(f"   Pessimistic bound, attributing the entire live set to levels: "
         f"{m.live.min() / m.lv[m.live.idxmin()]:.0f} B/level.")
    pess = m.live.min() / m.lv[m.live.idxmin()]
    emit("\n   forecast of the LIVE SET (add GC headroom and non-orderbook allocation on top).")
    emit("   'marginal' uses the fitted model; 'pessimistic' attributes the whole live set to levels:")
    base = w.totalLevels.iloc[-1]
    emit(f"     {'scale':>7} {'levels':>12} {'marginal':>10} {'pessimistic':>13}   (% of current Xmx)")
    for mult in (1, 2, 3, 5, 8):
        n = base * mult
        a = (icept + slope * n) / 2**30
        b = pess * n / 2**30
        emit(f"     {mult}x books {n:>12,.0f} {a:>7.2f} GiB {b:>10.2f} GiB   "
             f"({a / (xmx / 2**30) * 100:.0f}% / {b / (xmx / 2**30) * 100:.0f}%)")
    return slope, icept, m, w


# --------------------------------------------------------------------------- #
# 3. shard balance
# --------------------------------------------------------------------------- #
def shard_balance(shards, warmup: float):
    h1("3. SHARD BALANCE — structural or transient?")
    s = shards.copy()
    s["h"] = obcsv.hours_since_start(s["at"])
    s = s[s.h > warmup]
    ratios = {}
    for col in ("books", "totalLevels", "updatesPerSecond", "levelVisitsPerSecond"):
        p = s.pivot(index="at", columns="shard", values=col)
        r = p.max(axis=1) / p.min(axis=1)
        ratios[col] = r
        means = "  ".join(f"s{c} {p[c].mean():>11,.0f}" for c in p.columns)
        emit(f"{col:>21}  {means}   max/min: mean {r.mean():.3f} "
             f"p50 {r.median():.3f} min {r.min():.3f} max {r.max():.3f}")

    p = s.pivot(index="at", columns="shard", values="levelVisitsPerSecond")
    dominant = p.idxmax(axis=1)
    emit(f"\nthe same shard is the busier one on {dominant.value_counts(normalize=True).max() * 100:.1f}% "
         f"of ticks (shard {dominant.mode().iloc[0]})")
    emit("hourly mean of the levelVisitsPerSecond max/min ratio — is the gap drifting?")
    hourly = ratios["levelVisitsPerSecond"].resample("1h").mean()
    emit(textwrap.indent(hourly.round(3).to_string(), "  "))
    emit(f"\nlargest book per shard, by tick count:")
    emit(textwrap.indent(s.groupby("shard")["largestBook"].value_counts().to_string(), "  "))
    return ratios


# --------------------------------------------------------------------------- #
# 4. idle / stale threshold
# --------------------------------------------------------------------------- #
def idle(books, fleet, warmup: float):
    h1("4. IDLE DISTRIBUTION — calibrating stale-threshold-ms")
    b = books.copy()
    b["h"] = obcsv.hours_since_start(b["at"])
    b = b[(b.h > warmup) & (b.state == "SYNCED")]
    emit(f"n = {len(b):,} SYNCED book-observations over {b.h.max() - b.h.min():.1f} h")
    emit("NOTE idleMs has 5 s resolution (sample-interval-ms) — values are quantised.\n")

    qs = (.5, .75, .9, .95, .99, .999, .9999)
    for label, sub in (("ALL", b), ("SPOT", b[b.market == "SPOT"]), ("FUTURES", b[b.market == "FUTURES"])):
        v = sub.idleMs / 1000
        cells = "  ".join(f"p{q * 100:g}={v.quantile(q):>4.0f}s" for q in qs)
        emit(f"{label:>8} n={len(sub):>7,}  {cells}   max={v.max():.0f}s")

    emit("\ncandidate thresholds — what would fire:")
    emit(f"  {'thresh':>7} {'observations':>14} {'% of obs':>10} {'books':>7} {'ticks':>7}")
    for t in (15, 30, 45, 60, 90, 120, 150, 180, 240):
        hit = b[b.idleMs > t * 1000]
        emit(f"  {t:>6}s {len(hit):>14,} {len(hit) / len(b) * 100:>9.4f}% "
             f"{hit.groupby(['symbol', 'market']).ngroups:>7} {hit['at'].nunique():>7}")

    quiet = b[b.idleMs > 60_000]
    if len(quiet):
        emit("\nbooks that ever exceeded 60 s idle:")
        agg = quiet.groupby(["symbol", "market"]).agg(
            obs=("idleMs", "size"), max_idle_s=("idleMs", lambda x: x.max() / 1000),
            mean_rate=("updatesPerSecond", "mean"), mean_size=("size", "mean"))
        emit(textwrap.indent(agg.sort_values("max_idle_s", ascending=False).head(20).round(3).to_string(), "  "))
        emit(f"  market split of these books: "
             f"{agg.reset_index().market.value_counts().to_dict()}")

    f = fleet.copy()
    f["h"] = obcsv.hours_since_start(f["at"])
    f = f[f.h > warmup]
    emit(f"\nfleet staleBooks (server threshold as configured): "
         f"{(f.staleBooks > 0).sum()} of {len(f)} ticks non-zero "
         f"({(f.staleBooks > 0).mean() * 100:.2f}%), max {f.staleBooks.max()}")
    emit(f"fleet emptyBooks: max {f.emptyBooks.max()}")

    emit("\nidleMs by hour-of-day (UTC):")
    b2 = b.copy()
    b2["hod"] = b2["at"].dt.hour
    emit(textwrap.indent(b2.groupby("hod")["idleMs"].agg(
        p99=lambda x: x.quantile(.99) / 1000,
        p999=lambda x: x.quantile(.999) / 1000,
        max_s=lambda x: x.max() / 1000, n="size").round(1).to_string(), "  "))
    return b


# --------------------------------------------------------------------------- #
# 5. resyncs
# --------------------------------------------------------------------------- #
def resyncs(books, fleet, warmup: float):
    h1("5. RESYNCS — has any failure path ever fired?")
    b = obcsv.add_measured_rate(books)
    total_delta = b["d_resyncs"].sum()
    emit(f"cumulative resyncs at end of capture (max over books): {books.resyncs.max()}")
    emit(f"sum of positive per-book resync deltas across the capture: {total_delta:.0f}")
    emit(f"fleet resyncsLastHour: max {fleet.resyncsLastHour.max()}, "
         f"non-zero on {(fleet.resyncsLastHour > 0).sum()} of {len(fleet)} ticks")
    if total_delta == 0:
        exposure = books.groupby(["symbol", "market"]).ngroups * \
            (books["at"].max() - books["at"].min()).total_seconds() / 3600
        emit(f"\nZERO resyncs across {books.groupby(['symbol','market']).ngroups} books "
             f"x {(books['at'].max() - books['at'].min()).total_seconds() / 3600:.1f} h "
             f"= {exposure:,.0f} book-hours of exposure.")
        d = b["d_updates"].sum()
        emit(f"Diffs applied over the capture (sum of counter deltas): {d:,.0f}")
        emit("Every failure path funnels through resync(); none fired.")
    else:
        offenders = b[b.d_resyncs > 0]
        emit(textwrap.indent(offenders.groupby(["symbol", "market"])["d_resyncs"]
                             .sum().sort_values(ascending=False).head(20).to_string(), "  "))
    return b


# --------------------------------------------------------------------------- #
# 6. load & headroom
# --------------------------------------------------------------------------- #
def load_headroom(books, fleet, warmup: float):
    h1("6. LOAD AND HEADROOM")
    f = fleet.copy()
    f["h"] = obcsv.hours_since_start(f["at"])
    w = f[f.h > warmup].set_index("at")
    for c in ("updatesPerSecond", "levelVisitsPerSecond", "totalLevels"):
        v = w[c]
        emit(f"{c:>21}  mean {v.mean():>11,.0f}  p05 {v.quantile(.05):>11,.0f}  "
             f"p50 {v.median():>11,.0f}  p95 {v.quantile(.95):>11,.0f}  max {v.max():>11,.0f}  "
             f"peak/mean {v.max() / v.mean():.2f}")

    n_spot = books[books.market == "SPOT"].symbol.nunique()
    n_fut = books[books.market == "FUTURES"].symbol.nunique()
    nominal = n_spot * 1 + n_fut * 2
    emit(f"\nfleet: {n_spot} SPOT (1 diff/s cap) + {n_fut} FUTURES (2 diff/s cap) "
         f"=> nominal ceiling {nominal:,} diffs/s")
    emit(f"  observed mean {w.updatesPerSecond.mean():,.0f} "
         f"({w.updatesPerSecond.mean() / nominal * 100:.1f}% of ceiling)")
    emit(f"  observed peak {w.updatesPerSecond.max():,.0f} "
         f"({w.updatesPerSecond.max() / nominal * 100:.1f}% of ceiling)")

    peak = w.loc[w.levelVisitsPerSecond.idxmax()]
    emit(f"\npeak levelVisitsPerSecond {peak.levelVisitsPerSecond:,.0f} at {peak.name} "
         f"(totalLevels {peak.totalLevels:,.0f}, updates/s {peak.updatesPerSecond:,.0f})")
    emit("\nwork decomposition, first 30 min of the measured window vs last 30 min:")
    a, z = w.head(60), w.tail(60)
    emit(f"  levels {a.totalLevels.mean():>9,.0f} -> {z.totalLevels.mean():>9,.0f} "
         f"({z.totalLevels.mean() / a.totalLevels.mean() * 100 - 100:+.1f}%)")
    emit(f"  rate   {a.updatesPerSecond.mean():>9,.0f} -> {z.updatesPerSecond.mean():>9,.0f} "
         f"({z.updatesPerSecond.mean() / a.updatesPerSecond.mean() * 100 - 100:+.1f}%)")
    emit(f"  work   {a.levelVisitsPerSecond.mean():>9,.0f} -> {z.levelVisitsPerSecond.mean():>9,.0f} "
         f"({z.levelVisitsPerSecond.mean() / a.levelVisitsPerSecond.mean() * 100 - 100:+.1f}%)")
    emit("  -> work growth is driven by size, not by message rate.")

    emit("\nhourly fleet profile (UTC):")
    emit(textwrap.indent(w.resample("1h").agg(
        books=("books", "mean"), levels=("totalLevels", "mean"),
        ups=("updatesPerSecond", "mean"), lvps=("levelVisitsPerSecond", "mean"),
        heapMiB=("usedHeapBytes", lambda x: x.mean() / 2**20)).round(0).to_string(), "  "))
    return w


# --------------------------------------------------------------------------- #
# 7. work concentration & bid tilt
# --------------------------------------------------------------------------- #
def concentration_and_tilt(books, warmup: float):
    h1("7. WORK CONCENTRATION AND BID/ASK LEVEL TILT")
    b = books.copy()
    b["h"] = obcsv.hours_since_start(b["at"])
    b = b[(b.h > warmup) & (b.state == "SYNCED") & (b["size"] > 0)]
    b["work"] = b["size"] * b.updatesPerSecond
    b["tilt"] = (b.bids - b.asks) / b["size"]

    pb = b.groupby(["symbol", "market"]).agg(
        size=("size", "mean"), rate=("updatesPerSecond", "mean"),
        work=("work", "mean"), tilt=("tilt", "mean")).sort_values("work", ascending=False)
    tot = pb.work.sum()
    emit(f"time-averaged fleet work: {tot:,.0f} level visits/s across {len(pb)} books")
    emit("top 10 books by mean work:")
    show = pb.head(10).copy()
    show["share_%"] = (show.work / tot * 100).round(2)
    emit(textwrap.indent(show.round(3).to_string(), "  "))
    emit("")
    for frac in (.01, .05, .10, .25):
        k = max(1, int(len(pb) * frac))
        emit(f"  top {frac * 100:>4.0f}% of books ({k:>3}): "
             f"{pb.head(k).work.sum() / tot * 100:5.1f}% of work, "
             f"{pb.head(k)['size'].sum() / pb['size'].sum() * 100:5.1f}% of levels")
    emit(f"  Gini(work) {obcsv.gini(pb.work):.3f}   Gini(levels) {obcsv.gini(pb['size']):.3f}")
    ok = (pb['size'] > 0) & (pb.rate > 0)
    emit(f"  r(log size, log rate) = {np.corrcoef(np.log(pb['size'][ok]), np.log(pb.rate[ok]))[0, 1]:+.3f}")

    emit("\n-- bid/ask level tilt (bids - asks) / size --")
    ts = b.groupby("at")["tilt"].median()
    emit(f"fleet median tilt over time: mean {ts.mean():+.4f}  min {ts.min():+.4f}  "
         f"max {ts.max():+.4f}  std {ts.std():.4f}")
    emit(f"autocorrelation of the fleet median: lag1(1min) {ts.autocorr(1):.3f}  "
         f"lag10(10min) {ts.autocorr(10):.3f}  lag60(1h) {ts.autocorr(60):.3f}")
    emit(f"per-book mean tilt: {(pb.tilt > 0).sum()} positive / {(pb.tilt < 0).sum()} negative, "
         f"median {pb.tilt.median():+.4f}")

    piv = b.pivot_table(index="at", columns=["symbol", "market"], values="tilt").dropna(axis=1)
    if piv.shape[1] > 2:
        med = piv.median(axis=1)
        corr = piv.apply(lambda c: c.corr(med))
        X = ((piv - piv.mean()) / piv.std()).dropna(axis=1)
        sv = np.linalg.svd(X.values - X.values.mean(0), compute_uv=False)
        ev = sv**2 / (sv**2).sum()
        emit(f"cross-book synchrony: mean r vs fleet median {corr.mean():+.3f}; "
             f"{(corr > 0).mean() * 100:.0f}% of books positively correlated")
        emit(f"variance explained by PC1 across {X.shape[1]} tilt series: {ev[0] * 100:.1f}% "
             f"(PC2 {ev[1] * 100:.1f}%) — a common market factor, but mostly idiosyncratic")

    emit("\ntilt of the deepest books (mean size > 3000) — where the asymmetry concentrates:")
    deep = b[b.groupby(["symbol", "market"])["size"].transform("mean") > 3000]
    emit(textwrap.indent(deep.groupby(["symbol", "market"]).agg(
        size=("size", "mean"), bids=("bids", "mean"), asks=("asks", "mean"),
        tilt=("tilt", "mean"), tilt_min=("tilt", "min"), tilt_max=("tilt", "max"),
        r_size_tilt=("tilt", lambda x: np.nan)).round(3).drop(columns="r_size_tilt").to_string(), "  "))

    for key in (("BTCUSDT", "SPOT"), ("ETHUSDT", "SPOT"), ("BTCUSDT", "FUTURES")):
        d = b[(b.symbol == key[0]) & (b.market == key[1])]
        if len(d) > 2:
            emit(f"  r(size, tilt) within {key[0]}/{key[1]}: {np.corrcoef(d['size'], d.tilt)[0, 1]:+.3f}")
    emit("  -> tilt rises with a book's own fill, i.e. it measures where price has been,")
    emit("     not a defect in the symmetric mid*(1 +- threshold) sweep.")
    return pb, b


# --------------------------------------------------------------------------- #
# plots
# --------------------------------------------------------------------------- #
def make_plots(outdir, books, fleet, shards, growth_j, growth_fit, mem, idle_b, warmup):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.ticker as mtick

    plt.rcParams.update({
        "figure.facecolor": SURFACE, "axes.facecolor": SURFACE,
        "axes.edgecolor": GRID, "axes.labelcolor": INK2, "text.color": INK,
        "xtick.color": MUTED, "ytick.color": MUTED,
        "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8,
        "axes.spines.top": False, "axes.spines.right": False,
        "font.size": 10, "axes.titlesize": 12, "lines.linewidth": 2,
    })

    def finish(ax, title, sub=None):
        ax.set_title(title, loc="left", pad=26 if sub else 8, color=INK, fontweight="bold")
        if sub:
            ax.text(0, 1.015, sub, transform=ax.transAxes, color=MUTED, fontsize=9, va="bottom")
        ax.set_axisbelow(True)

    # --- 1. growth decomposition -------------------------------------------
    b = books[books.state == "SYNCED"].copy()
    b["h"] = obcsv.hours_since_start(b["at"])
    top10 = growth_j.sort_values("growth", ascending=False).head(10).index
    key = pd.MultiIndex.from_arrays([b.symbol, b.market])
    b["grp"] = np.where(key.isin(top10), "top10", "rest")
    series = b.pivot_table(index="at", columns="grp", values="size", aggfunc="sum")
    hrs = obcsv.hours_since_start(pd.Series(series.index))

    fig, axes = plt.subplots(1, 2, figsize=(13, 4.8))
    ax = axes[0]
    ax.plot(hrs, series["rest"] / 1000, color=C_BLUE, label="876 other books")
    ax.plot(hrs, series["top10"] / 1000, color=C_ORANGE, label="10 largest growers")
    ax.annotate("876 other books", (hrs.iloc[-1], series["rest"].iloc[-1] / 1000),
                xytext=(-6, 8), textcoords="offset points", ha="right", color=C_BLUE, fontsize=9,
                fontweight="bold")
    ax.annotate("10 largest growers", (hrs.iloc[-1], series["top10"].iloc[-1] / 1000),
                xytext=(-6, 8), textcoords="offset points", ha="right", color=C_ORANGE, fontsize=9,
                fontweight="bold")
    ax.axvspan(0, warmup, color=GRID, alpha=.6, lw=0)
    ax.text(warmup / 2, ax.get_ylim()[1] * .05, "warm-up", ha="center", color=MUTED, fontsize=8)
    ax.set_xlabel("hours since JVM start")
    ax.set_ylabel("levels (thousands)")
    ax.legend(frameon=False, loc="center right", fontsize=9)
    finish(ax, "Fleet growth is 10 books, not 886",
           "total price levels held, split by growth contribution")

    ax = axes[1]
    f = fleet.copy()
    f["h"] = obcsv.hours_since_start(f["at"])
    fw = f[f.h > 0.5]
    ax.plot(fw.h, fw.totalLevels / 1000, color=C_BLUE, label="observed")
    if growth_fit:
        sat, p = growth_fit
        tt = np.linspace(0.5, 24, 200)
        ax.plot(tt, sat(tt, *p) / 1000, color=C_ORANGE, ls="--", lw=2, label="saturating fit")
        ax.axhline(p[0] / 1000, color=MUTED, ls=":", lw=1.5)
        ax.annotate(f"asymptote {p[0] / 1000:,.0f}k", (24, p[0] / 1000), xytext=(-4, 6),
                    textcoords="offset points", ha="right", color=MUTED, fontsize=9)
        ax.annotate("observed", (fw.h.iloc[-1], fw.totalLevels.iloc[-1] / 1000), xytext=(6, -12),
                    textcoords="offset points", color=C_BLUE, fontsize=9, fontweight="bold")
        ax.annotate("fit, extrapolated", (20, sat(20, *p) / 1000), xytext=(-6, -18),
                    textcoords="offset points", ha="right", color=C_ORANGE, fontsize=9,
                    fontweight="bold")
    ax.set_xlabel("hours since JVM start")
    ax.set_ylabel("total levels (thousands)")
    ax.legend(frameon=False, loc="lower right", fontsize=9)
    finish(ax, "The band is close to saturated",
           f"exponential fit, tau {growth_fit[1][2]:.1f} h" if growth_fit else "")
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, "growth.png"), dpi=140)
    plt.close(fig)

    # --- 2. heap live set ---------------------------------------------------
    slope, icept, m, w = mem
    fig, axes = plt.subplots(1, 2, figsize=(13, 4.8))
    ax = axes[0]
    hh = obcsv.hours_since_start(pd.Series(w.index))
    ax.plot(hh, w.usedHeapBytes / 2**20, color=C_BLUE, lw=1, alpha=.5, label="usedHeapBytes")
    ax.plot(hh, obcsv.lower_envelope(w.usedHeapBytes, "10min") / 2**20, color=C_ORANGE,
            lw=2.2, label="10-min rolling min (live set)")
    ax.annotate("raw — GC sawtooth", (hh.iloc[len(hh) // 3], w.usedHeapBytes.iloc[len(hh) // 3] / 2**20),
                xytext=(0, 14), textcoords="offset points", color=C_BLUE, fontsize=9, fontweight="bold")
    ax.annotate("live set", (hh.iloc[-1], obcsv.lower_envelope(w.usedHeapBytes, "10min").iloc[-1] / 2**20),
                xytext=(-6, -16), textcoords="offset points", ha="right", color=C_ORANGE, fontsize=9,
                fontweight="bold")
    ax.set_xlabel("hours since JVM start")
    ax.set_ylabel("heap (MiB)")
    ax.legend(frameon=False, fontsize=9, loc="upper left")
    finish(ax, "A single heap reading is meaningless",
           f"sawtooth spans {(w.usedHeapBytes.max() - w.usedHeapBytes.min()) / 2**20:.0f} MiB")

    ax = axes[1]
    ax.scatter(m.lv / 1000, m.live / 2**20, s=9, color=C_BLUE, alpha=.35, edgecolors="none")
    xs = np.linspace(m.lv.min(), m.lv.max(), 50)
    ax.plot(xs / 1000, (icept + slope * xs) / 2**20, color=C_ORANGE, lw=2.2)
    ax.annotate(f"{slope:.0f} B / level\nbaseline {icept / 2**20:.0f} MiB",
                (xs[0] / 1000, (icept + slope * xs[0]) / 2**20), xytext=(10, 26),
                textcoords="offset points", ha="left", color=C_ORANGE, fontsize=9.5,
                fontweight="bold")
    ax.set_xlabel("total levels (thousands)")
    ax.set_ylabel("live-set heap (MiB)")
    finish(ax, "Bytes per level, from the lower envelope",
           "each point = one 30 s tick, heap floored over 10 min")
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, "heap.png"), dpi=140)
    plt.close(fig)

    # --- 3. shard imbalance -------------------------------------------------
    s = shards.copy()
    s["h"] = obcsv.hours_since_start(s["at"])
    s = s[s.h > warmup]
    fig, ax = plt.subplots(figsize=(9, 4.8))
    for col, colr, lab in ((["levelVisitsPerSecond"][0], C_ORANGE, "levelVisitsPerSecond (real work)"),
                           ("books", C_BLUE, "books (what looks balanced)")):
        p = s.pivot(index="at", columns="shard", values=col)
        r = (p.max(axis=1) / p.min(axis=1)).resample("10min").mean()
        hx = obcsv.hours_since_start(pd.Series(r.index))
        ax.plot(hx, r.values, color=colr, label=lab)
        ax.annotate(lab.split(" (")[0], (hx.iloc[-1], r.values[-1]), xytext=(-6, 8),
                    textcoords="offset points", ha="right", color=colr, fontsize=9, fontweight="bold")
    ax.axhline(1.0, color=MUTED, ls=":", lw=1.5)
    ax.annotate("perfect balance", (0.2, 1.0), xytext=(0, -14), textcoords="offset points",
                color=MUTED, fontsize=9)
    ax.set_ylim(0.96, None)
    ax.set_xlabel("hours since JVM start")
    ax.set_ylabel("busier shard / quieter shard")
    ax.yaxis.set_major_formatter(mtick.FormatStrFormatter("%.2fx"))
    ax.legend(frameon=False, fontsize=9, loc="center left")
    finish(ax, "The shard imbalance is structural, and widening",
           "2 shards; book count says balanced, work says otherwise")
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, "shards.png"), dpi=140)
    plt.close(fig)

    # --- 4. idle CCDF -------------------------------------------------------
    fig, ax = plt.subplots(figsize=(9, 4.8))
    for (label, colr, anchor) in (("SPOT", C_BLUE, 70), ("FUTURES", C_ORANGE, 18)):
        v = np.sort(idle_b[idle_b.market == label].idleMs.values / 1000)
        ccdf = 1.0 - np.arange(len(v)) / len(v)
        ax.step(v, ccdf, where="post", color=colr, label=label)
        i = min(np.searchsorted(v, anchor), len(v) - 1)
        ax.annotate(label, (v[i], ccdf[i]), xytext=(10, 8), textcoords="offset points",
                    color=colr, fontsize=9.5, fontweight="bold")
    ax.set_yscale("log")
    for t, lab in ((45, "45 s — proposed from\nthe static sample"), (120, "120 s — configured")):
        ax.axvline(t, color=MUTED, ls=":", lw=1.5)
        ax.annotate(lab, (t, 0.32), xytext=(6, 0), textcoords="offset points",
                    color=MUTED, fontsize=8.5, va="center")
    ax.set_xlabel("idleMs (seconds; 5 s quantisation)")
    ax.set_ylabel("fraction of observations exceeding")
    ax.set_ylim(1e-5, 1.4)
    ax.legend(frameon=False, fontsize=9, loc="upper right")
    finish(ax, "Idle time is a spot-market tail, not a fault signal",
           f"{len(idle_b):,} SYNCED observations over {idle_b.h.max() - idle_b.h.min():.1f} h")
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, "idle.png"), dpi=140)
    plt.close(fig)

    return ["growth.png", "heap.png", "shards.png", "idle.png"]


# --------------------------------------------------------------------------- #
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=obcsv.DEFAULT_DIR)
    ap.add_argument("--out", default="monitoring-analysis")
    ap.add_argument("--warmup-hours", type=float, default=1.0,
                    help="drop this much of the run before fitting anything (EWMA warm-up + "
                         "initial snapshot fill); see orderbook-monitoring.md 8.5")
    ap.add_argument("--no-plots", action="store_true")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    books, fleet, shards = obcsv.load_all(args.dir)

    capture_integrity(books, fleet, shards)
    growth_j, growth_fit = growth(books, fleet, args.warmup_hours)
    mem = memory(fleet, args.warmup_hours)
    shard_balance(shards, args.warmup_hours)
    idle_b = idle(books, fleet, args.warmup_hours)
    resyncs(books, fleet, args.warmup_hours)
    load_headroom(books, fleet, args.warmup_hours)
    concentration_and_tilt(books, args.warmup_hours)

    if not args.no_plots:
        try:
            figs = make_plots(args.out, books, fleet, shards, growth_j, growth_fit,
                              mem, idle_b, args.warmup_hours)
            h1("FIGURES")
            for f in figs:
                emit(f"  {os.path.join(args.out, f)}")
        except ImportError as e:
            emit(f"\n(plots skipped: {e})")

    report = os.path.join(args.out, "report.txt")
    with open(report, "w", encoding="utf-8") as fh:
        fh.write("\n".join(SECTIONS))
    print(f"\nreport written to {report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
