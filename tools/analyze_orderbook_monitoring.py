#!/usr/bin/env python3
"""
Fetch and analyse the live orderbook-fleet monitoring endpoints.

Unlike `plot_orderbook_histogram.py`, this script does everything itself: it logs in, fetches
`GET /api/monitoring/orderbook` (several parameter variants) and `GET /api/monitoring/orderbook/books`
(the whole fleet), saves the raw JSON, recomputes every statistic client-side as a cross-check,
writes three figures, and prints a report.

It deliberately does NOT touch /orderbook/history.

Dependencies: numpy + matplotlib only (no requests, no scipy, no pandas).

Usage
-----
    python tools/analyze_orderbook_monitoring.py
    python tools/analyze_orderbook_monitoring.py --base-url http://localhost:8080 --out-dir out
    python tools/analyze_orderbook_monitoring.py --raw-dir saved   # re-analyse without refetching

Reference for what every field means: .claude/docs/orderbook-monitoring.md
"""

import argparse
import json
import math
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.ticker import FuncFormatter

# --------------------------------------------------------------------------------------------
# Production coordinates. Hardcoded on purpose — this is an admin diagnostic tool.
# --------------------------------------------------------------------------------------------
BASE_URL = "https://tc-screener.com"
ADMIN_EMAIL = "shoalievabubakr@gmail.com"
ADMIN_PASSWORD = "Test123!"

# Validated categorical palette (light mode), slots 1..8. Only the first three are all-pairs safe,
# which is why nothing here paints more than three categories at once.
C_BLUE = "#2a78d6"    # slot 1 -> SPOT
C_ORANGE = "#eb6834"  # slot 2 -> FUTURES
C_AQUA = "#1baf7a"    # slot 3 -> combined / third category
C_RED = "#e34948"     # status: critical
C_YELLOW = "#eda100"  # status: warning
INK = "#0b0b0b"
INK_2 = "#52514e"
INK_MUTED = "#8a8880"
GRID = "#e3e2de"
SURFACE = "#fcfcfb"

MARKET_COLOR = {"SPOT": C_BLUE, "FUTURES": C_ORANGE}


# ============================================================================================
# HTTP
# ============================================================================================

def _request(url, method="GET", body=None, token=None, timeout=60):
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:500]
        sys.exit(f"HTTP {e.code} from {url}\n{detail}")
    except urllib.error.URLError as e:
        sys.exit(f"could not reach {url}: {e.reason}")


def login(base_url, email, password):
    doc = _request(f"{base_url}/api/auth/login", "POST",
                   {"email": email, "password": password})
    token = doc.get("accessToken")
    if not token:
        sys.exit(f"login returned no accessToken: {doc}")
    return token


def get(base_url, token, path, **params):
    query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    url = f"{base_url}{path}" + (f"?{query}" if query else "")
    return _request(url, token=token)


# ============================================================================================
# Statistics — mirrors DescriptiveStats.java so the client-side numbers are comparable
# ============================================================================================

def pct(sorted_arr, p):
    """R-7 / linear interpolation — identical to numpy.percentile and the server's convention."""
    if len(sorted_arr) == 0:
        return None
    return float(np.percentile(sorted_arr, p * 100.0, method="linear"))


def skewness_g1(arr):
    """Adjusted Fisher-Pearson G1 == scipy.stats.skew(bias=False)."""
    n = len(arr)
    if n < 3:
        return None
    m = arr.mean()
    s = arr.std(ddof=1)
    if s == 0:
        return None
    return float(n / ((n - 1) * (n - 2)) * np.sum(((arr - m) / s) ** 3))


def shape_of(g1):
    if g1 is None:
        return None
    a = abs(g1)
    if a < 0.5:
        return "SYMMETRIC"
    if a < 1.0:
        return "MODERATE_LEFT_SKEW" if g1 < 0 else "MODERATE_RIGHT_SKEW"
    return "HIGH_LEFT_SKEW" if g1 < 0 else "HIGH_RIGHT_SKEW"


def describe(values):
    """Full stat block over a 1-D sample, using the server's definitions."""
    arr = np.asarray(values, dtype=float)
    n = len(arr)
    if n == 0:
        return {"n": 0}
    s = np.sort(arr)
    p25, p75 = pct(s, 0.25), pct(s, 0.75)
    med = pct(s, 0.50)
    g1 = skewness_g1(arr)
    return {
        "n": n,
        "sum": float(arr.sum()),
        "mean": float(arr.mean()),
        "median": med,
        "stdDev": float(arr.std(ddof=1)) if n > 1 else None,
        "min": float(s[0]),
        "max": float(s[-1]),
        "p10": pct(s, 0.10), "p25": p25, "p50": med,
        "p75": p75, "p90": pct(s, 0.90), "p95": pct(s, 0.95), "p99": pct(s, 0.99),
        "iqr": p75 - p25,
        "mad": float(np.median(np.abs(arr - med))),
        "skewness": g1,
        "shape": shape_of(g1),
    }


def gini(values):
    """Concentration of a non-negative quantity. 0 = perfectly even, 1 = one book holds everything."""
    arr = np.sort(np.asarray(values, dtype=float))
    arr = arr[arr >= 0]
    n = len(arr)
    total = arr.sum()
    if n == 0 or total == 0:
        return None
    idx = np.arange(1, n + 1)
    return float((2 * np.sum(idx * arr)) / (n * total) - (n + 1) / n)


# ============================================================================================
# Fetch / persist
# ============================================================================================

# The parameter variants worth having. `default` is what a dashboard would call; the rest exist so
# the report can compare populations and show what the domain/state knobs actually change.
FLEET_VARIANTS = {
    "default":      dict(state="SYNCED", bins=0, outliers=10, domain="P99"),
    "all_states":   dict(state="ALL", bins=0, outliers=10, domain="P99"),
    "domain_full":  dict(state="SYNCED", bins=0, outliers=10, domain="FULL"),
    "domain_fence": dict(state="SYNCED", bins=0, outliers=10, domain="FENCE"),
    "spot":         dict(state="SYNCED", bins=0, outliers=10, domain="P99", market="SPOT"),
    "futures":      dict(state="SYNCED", bins=0, outliers=10, domain="P99", market="FUTURES"),
}


def fetch_all(base_url, email, password, out_dir):
    token = login(base_url, email, password)
    print(f"authenticated against {base_url}")

    payload = {"fetchedAt": datetime.now(timezone.utc).isoformat(), "baseUrl": base_url, "fleet": {}}
    for name, params in FLEET_VARIANTS.items():
        payload["fleet"][name] = get(base_url, token, "/api/monitoring/orderbook", **params)
        print(f"  fetched /orderbook [{name}]")

    # The whole fleet, every state — the richest single artefact the API exposes.
    payload["books"] = get(base_url, token, "/api/monitoring/orderbook/books",
                           state="ALL", sort="SYMBOL", order="ASC", limit=2000)
    print(f"  fetched /orderbook/books ({payload['books']['returned']} rows)")

    os.makedirs(out_dir, exist_ok=True)
    raw_path = os.path.join(out_dir, "raw-monitoring.json")
    with open(raw_path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=1)
    print(f"  saved {raw_path}")
    return payload


# ============================================================================================
# Derived per-book table (plain dict of numpy arrays — pandas is not installed everywhere)
# ============================================================================================

def build_table(books_doc):
    rows = books_doc["books"]
    t = {
        "symbol": np.array([r["symbol"] for r in rows]),
        "market": np.array([r["market"] for r in rows]),
        "state": np.array([r["state"] for r in rows]),
        "size": np.array([r["size"] for r in rows], dtype=float),
        "bids": np.array([r["bids"] for r in rows], dtype=float),
        "asks": np.array([r["asks"] for r in rows], dtype=float),
        "updates": np.array([r["updates"] for r in rows], dtype=float),
        "rate": np.array([r["updatesPerSecond"] for r in rows], dtype=float),
        "idleMs": np.array([r["idleMs"] for r in rows], dtype=float),
        "resyncs": np.array([r["resyncs"] for r in rows], dtype=float),
    }
    t["work"] = t["size"] * t["rate"]           # levelVisitsPerSecond, per book
    t["label"] = np.array([f"{r['symbol']}/{r['market'][:3]}" for r in rows])
    return t


def mask(t, **kw):
    m = np.ones(len(t["size"]), dtype=bool)
    for k, v in kw.items():
        m &= (t[k] == v)
    return m


def sub(t, m):
    return {k: v[m] for k, v in t.items()}


# ============================================================================================
# Figure 1 — size distribution
# ============================================================================================

def style_axes(ax, title=None, xlabel=None, ylabel=None):
    if title:
        ax.set_title(title, fontsize=10.5, color=INK, loc="left", pad=8)
    if xlabel:
        ax.set_xlabel(xlabel, fontsize=9, color=INK_2)
    if ylabel:
        ax.set_ylabel(ylabel, fontsize=9, color=INK_2)
    ax.grid(axis="y", color=GRID, linewidth=0.8, zorder=0)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)
    ax.tick_params(colors=INK_2, labelsize=8.5, length=0)


def draw_server_histogram(ax, hist, block):
    buckets = hist.get("buckets") or []
    if not buckets:
        ax.text(0.5, 0.5, "empty sample", ha="center", va="center", transform=ax.transAxes)
        return
    lefts = [b["from"] for b in buckets]
    widths = [(b["to"] - b["from"]) for b in buckets]
    counts = [b["count"] for b in buckets]
    # 2px surface gap between adjacent bars.
    ax.bar(lefts, counts, width=[w * 0.94 for w in widths], align="edge",
           color=C_AQUA, edgecolor=SURFACE, linewidth=0.6, zorder=3)

    overflow = hist.get("overflowCount", 0)
    if overflow:
        w = widths[-1]
        ax.bar([hist["upper"] + w * 0.6], [overflow], width=w * 0.94, align="edge",
               color=C_RED, edgecolor=SURFACE, linewidth=0.6, zorder=3)
        ax.annotate(f"overflow {overflow} books\n(max {hist['sampleMax']:.0f})",
                    xy=(hist["upper"] + w * 1.1, overflow), xytext=(0, 14),
                    textcoords="offset points", fontsize=8, color=C_RED, ha="center")

    # Staggered vertically so the two reference labels never collide on a tight body.
    for value, label, color, style, dy in [
        (block.get("median"), f"median {block['median']:.0f}", INK, "-", -12),
        (block.get("mean"), f"mean {block['mean']:.0f}", INK_MUTED, "--", -28),
    ]:
        if value is not None:
            ax.axvline(value, color=color, linestyle=style, linewidth=1.6, zorder=4)
            ax.annotate(label, xy=(value, ax.get_ylim()[1]), xytext=(5, dy),
                        textcoords="offset points", fontsize=8, color=color)

    style_axes(ax, f"server histogram, domain={hist['domain']} ({hist['method']}, {hist['bins']} bins)",
               "book size (bids + asks)", "books")


def draw_log_hist_by_market(ax, t):
    live = t["size"] > 0
    everything = t["size"][live]
    if len(everything) == 0:
        return
    edges = np.logspace(np.log10(everything.min()), np.log10(everything.max()), 46)
    series, labels, colors = [], [], []
    for mkt in ("SPOT", "FUTURES"):
        v = t["size"][live & (t["market"] == mkt)]
        series.append(v)
        labels.append(f"{mkt} (n={len(v)})")
        colors.append(MARKET_COLOR[mkt])
    ax.hist(series, bins=edges, stacked=True, label=labels, color=colors,
            edgecolor=SURFACE, linewidth=0.4, zorder=3)
    ax.set_xscale("log")
    style_axes(ax, "same sample on a log axis, split by market",
               "book size, log scale", "books")
    ax.legend(fontsize=8, frameon=False, labelcolor=INK_2)


def draw_ecdf(ax, t):
    # SPOT's median sits left of FUTURES', so its label goes left of the marker and FUTURES' right —
    # otherwise the two run into each other at y = 0.5.
    for mkt, dx, ha in (("SPOT", -10, "right"), ("FUTURES", 10, "left")):
        v = np.sort(t["size"][(t["market"] == mkt) & (t["size"] > 0)])
        if len(v) == 0:
            continue
        y = np.arange(1, len(v) + 1) / len(v)
        ax.step(v, y, where="post", color=MARKET_COLOR[mkt], linewidth=2, zorder=3, label=mkt)
        med = np.median(v)
        ax.plot([med], [0.5], "o", ms=8, color=MARKET_COLOR[mkt],
                markeredgecolor=SURFACE, markeredgewidth=2, zorder=4)
        ax.annotate(f"{mkt} median {med:.0f}", xy=(med, 0.5), xytext=(dx, 8),
                    textcoords="offset points", fontsize=8, color=MARKET_COLOR[mkt], ha=ha)
    ax.set_xscale("log")
    ax.set_ylim(0, 1.02)
    ax.axhline(0.5, color=GRID, linewidth=1, zorder=1)
    style_axes(ax, "cumulative distribution of book size", "book size, log scale",
               "fraction of books at or below")
    ax.legend(fontsize=8, frameon=False, labelcolor=INK_2, loc="lower right")


def draw_side_balance(ax, t):
    live = (t["size"] > 0)
    frac = (t["bids"][live] - t["asks"][live]) / t["size"][live]
    mkts = t["market"][live]
    bins = np.linspace(-1, 1, 61)
    series = [frac[mkts == m] for m in ("SPOT", "FUTURES")]
    ax.hist(series, bins=bins, stacked=True,
            label=[f"SPOT (n={len(series[0])})", f"FUTURES (n={len(series[1])})"],
            color=[C_BLUE, C_ORANGE], edgecolor=SURFACE, linewidth=0.4, zorder=3)
    ax.axvline(0, color=INK, linewidth=1.4, zorder=4)
    ax.annotate("balanced", xy=(0, ax.get_ylim()[1]), xytext=(5, -12),
                textcoords="offset points", fontsize=8, color=INK)
    style_axes(ax, "bid/ask level asymmetry  (bids - asks) / size",
               "< 0 = deeper ask side          > 0 = deeper bid side", "books")
    ax.legend(fontsize=8, frameon=False, labelcolor=INK_2)


def figure_sizes(payload, t, out_dir):
    fleet = payload["fleet"]["default"]
    synced = sub(t, t["state"] == "SYNCED")

    fig, axes = plt.subplots(2, 2, figsize=(14, 9), facecolor=SURFACE)
    for ax in axes.ravel():
        ax.set_facecolor(SURFACE)

    draw_server_histogram(axes[0][0], fleet["histogram"], fleet["overall"])
    draw_log_hist_by_market(axes[0][1], synced)
    draw_ecdf(axes[1][0], synced)
    draw_side_balance(axes[1][1], synced)

    totals = fleet["totals"]
    fig.suptitle(
        f"Orderbook fleet — size distribution   |   {totals['books']} books, "
        f"{totals['totalLevels']:,} levels, {totals['syncedRatio'] * 100:.1f}% synced   |   "
        f"{payload['fetchedAt'][:19]}Z",
        fontsize=13, color=INK, x=0.01, ha="left")
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    path = os.path.join(out_dir, "01-size-distribution.png")
    fig.savefig(path, dpi=140, facecolor=SURFACE)
    plt.close(fig)
    return path


# ============================================================================================
# Figure 2 — update rate and where the work actually is
# ============================================================================================

def draw_rate_hist(ax, t):
    live = t["rate"] > 0
    bins = np.linspace(0, max(2.2, t["rate"][live].max() if live.any() else 2.2), 60)
    series = [t["rate"][live & (t["market"] == m)] for m in ("SPOT", "FUTURES")]
    # Drawn one at a time so the legend order matches the reading order (spot first).
    for v, mkt in zip(series, ("SPOT", "FUTURES")):
        ax.hist(v, bins=bins, histtype="stepfilled", alpha=0.75,
                label=f"{mkt} (n={len(v)})", color=MARKET_COLOR[mkt],
                edgecolor=SURFACE, linewidth=0.6, zorder=3)
    for x, label, color in [(1.0, "spot stream ceiling 1/s", C_BLUE),
                            (2.0, "futures stream ceiling 2/s", C_ORANGE)]:
        ax.axvline(x, color=color, linestyle=":", linewidth=1.6, zorder=4)
        ax.annotate(label, xy=(x, ax.get_ylim()[1]), xytext=(-4, -12), rotation=90,
                    textcoords="offset points", fontsize=7.5, color=color, ha="right", va="top")
    style_axes(ax, "per-book update rate (EWMA, 30 s half-life)",
               "diffs applied per second", "books")
    ax.legend(fontsize=8, frameon=False, labelcolor=INK_2)


def draw_work_scatter(ax, t):
    live = (t["size"] > 0) & (t["rate"] > 0)
    x_lo, x_hi = t["size"][live].min() * 0.7, t["size"][live].max() * 2.2
    y_lo, y_hi = t["rate"][live].min() * 0.5, 6.0

    for mkt in ("SPOT", "FUTURES"):
        m = live & (t["market"] == mkt)
        ax.scatter(t["size"][m], t["rate"][m], s=18, alpha=0.55,
                   color=MARKET_COLOR[mkt], edgecolor=SURFACE, linewidth=0.5,
                   label=f"{mkt} (n={m.sum()})", zorder=3)

    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlim(x_lo, x_hi)
    ax.set_ylim(y_lo, y_hi)

    # Iso-work contours: size x rate = const. This product, not the message count, is what costs CPU.
    # Each is labelled where it leaves the top of the axes, which is empty space in every real sample.
    xs = np.logspace(np.log10(x_lo), np.log10(x_hi), 60)
    for w in (100, 1_000, 10_000, 60_000):
        ax.plot(xs, w / xs, color=INK_MUTED, linewidth=0.9, linestyle="--", zorder=2)
        x_top = w / y_hi
        if x_lo < x_top < x_hi:
            ax.annotate(f"{w:,} lv/s", xy=(x_top, y_hi), xytext=(3, -10),
                        textcoords="offset points", fontsize=7, color=INK_MUTED, ha="left")

    # Name the heaviest books. They all cluster at the top right, so the labels are stacked downward
    # into the empty region beneath them with leader lines rather than sat on top of one another.
    top = np.argsort(t["work"])[::-1][:5]
    for k, i in enumerate(top):
        ax.annotate(f"{t['label'][i]}  {t['work'][i]:,.0f} lv/s",
                    xy=(t["size"][i], t["rate"][i]),
                    xytext=(-14, -26 - 15 * k), textcoords="offset points",
                    fontsize=7.5, color=INK, ha="right",
                    arrowprops=dict(arrowstyle="-", color=INK_MUTED, linewidth=0.7,
                                    shrinkA=0, shrinkB=3))

    style_axes(ax, "where the work is: size x rate = level visits per second",
               "book size, log", "updates/second, log")
    ax.legend(fontsize=8, frameon=False, labelcolor=INK_2, loc="lower left")


def draw_concentration(ax, t):
    """Cumulative share of total work / levels held by the top-k books."""
    for values, label, color in [(t["work"], "level visits / s (CPU)", C_ORANGE),
                                 (t["size"], "levels held (memory)", C_BLUE)]:
        v = np.sort(np.asarray(values, dtype=float))[::-1]
        total = v.sum()
        if total <= 0:
            continue
        share = np.cumsum(v) / total
        x = np.arange(1, len(v) + 1) / len(v)
        ax.plot(x * 100, share * 100, color=color, linewidth=2.2, zorder=3, label=label)
        g = gini(values)
        # Mark the top-10% point — the single most quotable number on this panel.
        k = max(1, int(0.10 * len(v)))
        ax.plot([10], [share[k - 1] * 100], "o", ms=9, color=color,
                markeredgecolor=SURFACE, markeredgewidth=2, zorder=4)
        ax.annotate(f"top 10% of books = {share[k - 1] * 100:.0f}%  (gini {g:.2f})",
                    xy=(10, share[k - 1] * 100), xytext=(10, -12 if color == C_BLUE else 6),
                    textcoords="offset points", fontsize=8.5, color=color)
    ax.plot([0, 100], [0, 100], color=GRID, linewidth=1.4, zorder=1)
    ax.annotate("perfectly even", xy=(70, 70), xytext=(4, -14), textcoords="offset points",
                fontsize=7.5, color=INK_MUTED)
    ax.set_xlim(0, 100)
    ax.set_ylim(0, 101)
    style_axes(ax, "concentration: how few books carry the fleet",
               "books, ranked heaviest first (%)", "cumulative share of total (%)")
    ax.legend(fontsize=8, frameon=False, labelcolor=INK_2, loc="lower right")


def draw_top_work(ax, t, k=18):
    order = np.argsort(t["work"])[::-1][:k]
    y = np.arange(len(order))[::-1]
    colors = [MARKET_COLOR[m] for m in t["market"][order]]
    ax.barh(y, t["work"][order], height=0.72, color=colors,
            edgecolor=SURFACE, linewidth=0.6, zorder=3)
    ax.set_yticks(y)
    ax.set_yticklabels(t["label"][order], fontsize=7.5, color=INK_2)
    total = t["work"].sum()
    for yi, i in zip(y, order):
        ax.annotate(f"{t['work'][i]:,.0f}  ({t['work'][i] / total * 100:.1f}%)",
                    xy=(t["work"][i], yi), xytext=(5, 0), textcoords="offset points",
                    fontsize=7.5, color=INK_2, va="center")
    ax.set_xlim(0, t["work"][order].max() * 1.28)
    style_axes(ax, f"heaviest {k} books by level visits per second",
               "levels swept per second", None)
    ax.grid(axis="y", visible=False)
    ax.grid(axis="x", color=GRID, linewidth=0.8)


def figure_work(payload, t, out_dir):
    synced = sub(t, t["state"] == "SYNCED")
    fig, axes = plt.subplots(2, 2, figsize=(14, 9.5), facecolor=SURFACE)
    for ax in axes.ravel():
        ax.set_facecolor(SURFACE)

    draw_rate_hist(axes[0][0], synced)
    draw_work_scatter(axes[0][1], synced)
    draw_concentration(axes[1][0], synced)
    draw_top_work(axes[1][1], synced)

    totals = payload["fleet"]["default"]["totals"]
    fig.suptitle(
        f"Orderbook fleet — update rate and CPU work   |   "
        f"{totals['updatesPerSecond']:,.0f} diffs/s, "
        f"{totals['levelVisitsPerSecond']:,.0f} level visits/s",
        fontsize=13, color=INK, x=0.01, ha="left")
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    path = os.path.join(out_dir, "02-work-and-rates.png")
    fig.savefig(path, dpi=140, facecolor=SURFACE)
    plt.close(fig)
    return path


# ============================================================================================
# Figure 3 — health, staleness, shard balance
# ============================================================================================

def draw_shard_balance(ax, shards):
    idx = [s["shard"] for s in shards]
    metrics = [
        ("books", np.array([s["books"] for s in shards], dtype=float), C_BLUE),
        ("total levels", np.array([s["totalLevels"] for s in shards], dtype=float), C_AQUA),
        ("level visits/s", np.array([s["levelVisitsPerSecond"] for s in shards], dtype=float), C_ORANGE),
    ]
    width = 0.26
    xs = np.arange(len(idx))
    for j, (label, v, color) in enumerate(metrics):
        norm = v / v.mean() if v.mean() else v
        ax.bar(xs + (j - 1) * width, norm, width=width * 0.92, color=color,
               edgecolor=SURFACE, linewidth=0.6, label=label, zorder=3)
        for x, val, raw in zip(xs + (j - 1) * width, norm, v):
            ax.annotate(f"{val:.2f}x", xy=(x, val), xytext=(0, 3), textcoords="offset points",
                        fontsize=7.5, color=INK_2, ha="center")
    ax.axhline(1.0, color=INK, linewidth=1.2, zorder=4)
    ax.set_xticks(xs)
    ax.set_xticklabels([f"shard {i}" for i in idx], fontsize=9, color=INK_2)
    style_axes(ax, "shard balance, each metric normalised to its own mean",
               None, "relative to the mean shard")
    ax.legend(fontsize=8, frameon=False, labelcolor=INK_2)


def draw_idle_ecdf(ax, t, stale_threshold_ms=120_000):
    synced = sub(t, t["state"] == "SYNCED")
    v = np.sort(synced["idleMs"])
    if len(v) == 0:
        return
    y = np.arange(1, len(v) + 1) / len(v)
    ax.step(np.maximum(v, 1) / 1000.0, y * 100, where="post", color=C_BLUE, linewidth=2.2, zorder=3)
    ax.set_xscale("symlog", linthresh=1)
    thr = stale_threshold_ms / 1000.0
    ax.axvline(thr, color=C_RED, linewidth=1.6, linestyle="--", zorder=4)
    above = (synced["idleMs"] > stale_threshold_ms).sum()
    ax.annotate(f"stale-threshold {thr:.0f}s\n{above} books beyond ({above / len(v) * 100:.1f}%)",
                xy=(thr, 40), xytext=(8, 0), textcoords="offset points",
                fontsize=8, color=C_RED, va="center")
    for q in (50, 90, 99):
        xq = np.percentile(v, q) / 1000.0
        ax.plot([xq], [q], "o", ms=7, color=C_BLUE, markeredgecolor=SURFACE,
                markeredgewidth=2, zorder=5)
        ax.annotate(f"p{q} = {xq:.0f}s", xy=(xq, q), xytext=(8, -10),
                    textcoords="offset points", fontsize=7.5, color=INK_2)
    ax.set_ylim(0, 101)
    style_axes(ax, "idle time of SYNCED books (5 s resolution)",
               "seconds since a diff was last observed, symlog", "% of synced books")


def draw_resyncs(ax, t, k=16):
    with_resync = sub(t, t["resyncs"] > 0)
    if len(with_resync["resyncs"]) == 0:
        # Zero resyncs is the headline, but an empty quadrant wastes the space — show cumulative
        # diffs applied instead, which is the same books' total throughput over the whole run.
        live = t["updates"] > 0
        for mkt in ("SPOT", "FUTURES"):
            v = t["updates"][live & (t["market"] == mkt)]
            if len(v) == 0:
                continue
            edges = np.logspace(np.log10(t["updates"][live].min()),
                                np.log10(t["updates"][live].max()), 40)
            ax.hist(v, bins=edges, histtype="stepfilled", alpha=0.75,
                    label=f"{mkt} (n={len(v)})", color=MARKET_COLOR[mkt],
                    edgecolor=SURFACE, linewidth=0.6, zorder=3)
        ax.set_xscale("log")
        ax.annotate(f"0 resyncs across all {len(t['resyncs'])} books since JVM start",
                    xy=(0.98, 0.94), xycoords="axes fraction", ha="right",
                    fontsize=9.5, color=C_AQUA)
        style_axes(ax, "no resyncs to plot — cumulative diffs applied per book instead",
                   "diffs applied since JVM start, log", "books")
        ax.legend(fontsize=8, frameon=False, labelcolor=INK_2, loc="upper left")
        return
    order = np.argsort(with_resync["resyncs"])[::-1][:k]
    y = np.arange(len(order))[::-1]
    colors = [MARKET_COLOR[m] for m in with_resync["market"][order]]
    ax.barh(y, with_resync["resyncs"][order], height=0.72, color=colors,
            edgecolor=SURFACE, linewidth=0.6, zorder=3)
    ax.set_yticks(y)
    ax.set_yticklabels(with_resync["label"][order], fontsize=7.5, color=INK_2)
    for yi, i in zip(y, order):
        ax.annotate(f"{with_resync['resyncs'][i]:.0f}", xy=(with_resync["resyncs"][i], yi),
                    xytext=(5, 0), textcoords="offset points", fontsize=7.5,
                    color=INK_2, va="center")
    ax.set_xlim(0, with_resync["resyncs"][order].max() * 1.18)
    total_books = len(t["resyncs"])
    style_axes(ax,
               f"resyncs since JVM start — {len(with_resync['resyncs'])} of {total_books} books "
               f"have resynced at least once",
               "cumulative resyncs", None)
    ax.grid(axis="y", visible=False)
    ax.grid(axis="x", color=GRID, linewidth=0.8)


def draw_state_breakdown(ax, t, fleet_all):
    states = ["SYNCED", "SNAPSHOT_REQUESTED", "PENDING"]
    colors = [C_AQUA, C_YELLOW, C_RED]
    markets = ["SPOT", "FUTURES"]
    xs = np.arange(len(markets))
    bottom = np.zeros(len(markets))
    for st, color in zip(states, colors):
        v = np.array([((t["market"] == m) & (t["state"] == st)).sum() for m in markets], dtype=float)
        ax.bar(xs, v, bottom=bottom, width=0.5, color=color, edgecolor=SURFACE,
               linewidth=2, label=st, zorder=3)
        for x, val, b in zip(xs, v, bottom):
            if val > 0:
                ax.annotate(f"{val:.0f}", xy=(x, b + val / 2), fontsize=8.5,
                            color=SURFACE if st != "SNAPSHOT_REQUESTED" else INK,
                            ha="center", va="center", zorder=4)
        bottom += v
    ax.set_xticks(xs)
    ax.set_xticklabels(markets, fontsize=9.5, color=INK_2)
    totals = fleet_all["totals"]
    style_axes(ax,
               f"sync state — {totals['staleBooks']} stale, {totals['emptyBooks']} empty, "
               f"{totals['resyncsLastHour']} resyncs in the last hour",
               None, "books")
    ax.legend(fontsize=8, frameon=False, labelcolor=INK_2)


def figure_health(payload, t, out_dir):
    fleet_all = payload["fleet"]["all_states"]
    fig, axes = plt.subplots(2, 2, figsize=(14, 9.5), facecolor=SURFACE)
    for ax in axes.ravel():
        ax.set_facecolor(SURFACE)

    draw_shard_balance(axes[0][0], fleet_all["byShard"])
    draw_idle_ecdf(axes[0][1], t)
    draw_state_breakdown(axes[1][0], t, fleet_all)
    draw_resyncs(axes[1][1], t)

    totals = fleet_all["totals"]
    heap_pct = totals["usedHeapBytes"] / totals["maxHeapBytes"] * 100
    fig.suptitle(
        f"Orderbook fleet — health, staleness, shard balance   |   heap "
        f"{totals['usedHeapBytes'] / 1e9:.2f} / {totals['maxHeapBytes'] / 1e9:.2f} GB ({heap_pct:.0f}%)   |   "
        f"sample age {fleet_all['sampleAgeMs']} ms, stale shards {fleet_all['shardsStale'] or 'none'}",
        fontsize=13, color=INK, x=0.01, ha="left")
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    path = os.path.join(out_dir, "03-health-and-shards.png")
    fig.savefig(path, dpi=140, facecolor=SURFACE)
    plt.close(fig)
    return path


# ============================================================================================
# Text report
# ============================================================================================

def fmt(v, nd=2):
    if v is None:
        return "—"
    if isinstance(v, float):
        return f"{v:,.{nd}f}"
    return f"{v:,}"


def report(payload, t):
    out = []
    w = out.append

    d = payload["fleet"]["default"]
    a = payload["fleet"]["all_states"]
    totals = a["totals"]

    w("=" * 100)
    w(f"ORDERBOOK FLEET ANALYSIS   fetched {payload['fetchedAt']}   base {payload['baseUrl']}")
    w("=" * 100)

    # ---- freshness ----------------------------------------------------------------------
    w("\n[1] SAMPLE FRESHNESS")
    w(f"  sample age (newest shard publication) : {a['sampleAgeMs']} ms")
    w(f"  stale shards                          : {a['shardsStale'] or 'none'}")
    w(f"  books returned by /books              : {payload['books']['returned']} "
      f"of {payload['books']['totalMatching']} matching")

    # ---- fleet totals -------------------------------------------------------------------
    w("\n[2] FLEET TOTALS  (state=ALL, both markets)")
    w(f"  books                    : {fmt(totals['books'])}   "
      f"{ {k: v for k, v in totals['byMarket'].items()} }")
    w(f"  by state                 : { {k: v for k, v in totals['byState'].items()} }   "
      f"syncedRatio {totals['syncedRatio'] * 100:.2f}%")
    w(f"  total price levels       : {fmt(totals['totalLevels'])}")
    w(f"  diffs applied / second   : {fmt(totals['updatesPerSecond'])}")
    w(f"  LEVEL VISITS / second    : {fmt(totals['levelVisitsPerSecond'])}   <- the real unit of work")
    w(f"  stale / empty books      : {totals['staleBooks']} stale, {totals['emptyBooks']} empty")
    w(f"  resyncs last hour        : {totals['resyncsLastHour']}")
    w(f"  heap                     : {totals['usedHeapBytes'] / 1e9:.2f} GB used of "
      f"{totals['maxHeapBytes'] / 1e9:.2f} GB max "
      f"({totals['usedHeapBytes'] / totals['maxHeapBytes'] * 100:.1f}%)")
    if totals["totalLevels"]:
        w(f"  crude bytes/level        : {totals['usedHeapBytes'] / totals['totalLevels']:,.0f} B "
          f"(single GC-sawtooth reading — treat as an upper bound)")

    # ---- uptime estimate ----------------------------------------------------------------
    live = (t["rate"] > 0) & (t["updates"] > 0)
    if live.any():
        implied = t["updates"][live] / t["rate"][live]
        w(f"\n  implied JVM uptime (median updates/rate over {live.sum()} books) : "
          f"{np.median(implied) / 3600:.2f} h   "
          f"[p25 {np.percentile(implied, 25) / 3600:.2f} h, p75 {np.percentile(implied, 75) / 3600:.2f} h]")

    # ---- size distribution --------------------------------------------------------------
    w("\n[3] BOOK SIZE DISTRIBUTION  (SYNCED only; size = bids + asks)")
    header = f"  {'population':<22} {'n':>5} {'median':>9} {'iqr':>9} {'mean':>10} {'stdDev':>10} " \
             f"{'mad*1.4826':>11} {'p90':>9} {'p99':>9} {'max':>9} {'skew':>7}  shape"
    w(header)
    w("  " + "-" * (len(header) - 2))
    for name, block in [("overall (pooled)", d["overall"]),
                        ("SPOT", d["byMarket"].get("SPOT")),
                        ("FUTURES", d["byMarket"].get("FUTURES"))]:
        if not block:
            continue
        rec = describe(t["size"][(t["state"] == "SYNCED") &
                                 (t["market"] == name if name in ("SPOT", "FUTURES")
                                  else np.ones(len(t["size"]), bool))])
        w(f"  {name:<22} {block['n']:>5} {fmt(block['median'], 0):>9} {fmt(block['iqr'], 0):>9} "
          f"{fmt(block['mean'], 0):>10} {fmt(block['stdDev'], 0):>10} "
          f"{fmt(block['mad'] * 1.4826, 0):>11} {fmt(block['p90'], 0):>9} "
          f"{fmt(rec.get('p99'), 0):>9} {fmt(block['max'], 0):>9} "
          f"{fmt(block['skewness'], 1):>7}  {block['shape']}")
    w("  note: stdDev / (mad*1.4826) is the outlier-inflation factor; > 2 means sigma is a fiction.")

    # server vs client cross-check
    synced_sizes = t["size"][t["state"] == "SYNCED"]
    rec = describe(synced_sizes)
    srv = d["overall"]
    w(f"\n  cross-check (recomputed from /books vs server 'overall'): "
      f"n {rec['n']} vs {srv['n']}, median {fmt(rec['median'], 1)} vs {fmt(srv['median'], 1)}, "
      f"mean {fmt(rec['mean'], 1)} vs {fmt(srv['mean'], 1)}, "
      f"skew {fmt(rec['skewness'], 3)} vs {fmt(srv['skewness'], 3)}")

    # ---- bid/ask symmetry ---------------------------------------------------------------
    synced0 = sub(t, (t["state"] == "SYNCED") & (t["size"] > 0))
    asym = (synced0["bids"] - synced0["asks"]) / synced0["size"]
    w("\n[3b] BID/ASK LEVEL SYMMETRY  ((bids - asks) / size, SYNCED books)")
    w(f"  all      : median {np.median(asym):+.4f}, mean {asym.mean():+.4f}, "
      f"p10 {np.percentile(asym, 10):+.3f}, p90 {np.percentile(asym, 90):+.3f}")
    for mkt in ("SPOT", "FUTURES"):
        v = asym[synced0["market"] == mkt]
        if len(v):
            w(f"  {mkt:<9}: median {np.median(v):+.4f}, "
              f"{(v > 0).sum()} bid-heavy / {(v < 0).sum()} ask-heavy / {(v == 0).sum()} exactly even")
    w(f"  |asymmetry| > 0.20 : {(np.abs(asym) > 0.20).sum()} books "
      f"({(np.abs(asym) > 0.20).sum() / len(asym) * 100:.1f}%)")
    w("  A symmetric price filter (+/-10% either side of mid) should give ~0 median. A persistent")
    w("  offset means one side saturates the band earlier than the other.")

    # ---- size vs rate coupling ----------------------------------------------------------
    w("\n[3c] SIZE / RATE COUPLING  (does the big book also update fastest?)")
    for name, m in (("all SYNCED", np.ones(len(synced0["size"]), bool)),
                    ("SPOT", synced0["market"] == "SPOT"),
                    ("FUTURES", synced0["market"] == "FUTURES")):
        s, r = synced0["size"][m], synced0["rate"][m]
        keep = (s > 0) & (r > 0)
        if keep.sum() < 3:
            continue
        rho = float(np.corrcoef(np.log(s[keep]), np.log(r[keep]))[0, 1])
        w(f"  {name:<12}: Pearson r on log(size) vs log(rate) = {rho:+.3f}  (n={keep.sum()})")
    w("  A positive value means work concentrates super-linearly: the deep books are also the busy")
    w("  ones, so levelVisits is more skewed than either factor alone.")

    # ---- histogram domains --------------------------------------------------------------
    w("\n[4] HISTOGRAM DOMAIN COMPARISON  (why P99 is the default)")
    w(f"  {'domain':<8} {'bins':>5} {'binWidth':>10} {'upper':>10} {'sampleMax':>10} "
      f"{'overflow':>9} {'empty bins':>11}  bins holding the modal half")
    for key in ("domain_full", "domain_fence", "default"):
        h = payload["fleet"][key]["histogram"]
        buckets = h.get("buckets") or []
        counts = np.array([b["count"] for b in buckets], dtype=float)
        empty = int((counts == 0).sum())
        # how many bins are needed to reach 50% of the bucketed sample
        order = np.argsort(counts)[::-1]
        need = int(np.searchsorted(np.cumsum(counts[order]) / max(counts.sum(), 1), 0.5) + 1)
        w(f"  {h['domain']:<8} {h['bins']:>5} {fmt(h['binWidth'], 1):>10} {fmt(h['upper'], 0):>10} "
          f"{fmt(h['sampleMax'], 0):>10} {h['overflowCount']:>9} {empty:>11}  {need}")

    # ---- update rates -------------------------------------------------------------------
    w("\n[5] UPDATE RATE  (per-book EWMA, diffs/second)")
    hdr = f"  {'population':<22} {'n':>5} {'median':>9} {'mean':>9} {'p10':>9} {'p90':>9} " \
          f"{'max':>9} {'sum':>12}  shape"
    w(hdr)
    w("  " + "-" * (len(hdr) - 2))
    for name, block in [("overall (pooled)", d["updateRate"]),
                        ("SPOT", d["updateRateByMarket"].get("SPOT")),
                        ("FUTURES", d["updateRateByMarket"].get("FUTURES"))]:
        if not block:
            continue
        w(f"  {name:<22} {block['n']:>5} {fmt(block['median'], 3):>9} {fmt(block['mean'], 3):>9} "
          f"{fmt(block['p10'], 3):>9} {fmt(block['p90'], 3):>9} {fmt(block['max'], 3):>9} "
          f"{fmt(block['sum'], 1):>12}  {block['shape']}")

    synced = sub(t, t["state"] == "SYNCED")
    for mkt, ceiling in (("SPOT", 1.0), ("FUTURES", 2.0)):
        v = synced["rate"][synced["market"] == mkt]
        if len(v) == 0:
            continue
        w(f"  {mkt}: {(v > ceiling * 0.95).sum()}/{len(v)} books at >=95% of the "
          f"{ceiling:.0f}/s stream ceiling; {(v < ceiling * 0.5).sum()} below half of it")

    # ---- work concentration -------------------------------------------------------------
    w("\n[6] WORK CONCENTRATION  (level visits per second = size x rate)")
    work = synced["work"]
    order = np.argsort(work)[::-1]
    total_work = work.sum()
    total_levels = synced["size"].sum()
    for frac in (0.01, 0.05, 0.10, 0.25):
        k = max(1, int(frac * len(work)))
        w(f"  top {frac * 100:>4.0f}% of books ({k:>4}) : "
          f"{np.sort(work)[::-1][:k].sum() / total_work * 100:>5.1f}% of level visits, "
          f"{np.sort(synced['size'])[::-1][:k].sum() / total_levels * 100:>5.1f}% of levels")
    w(f"  gini(level visits) = {gini(work):.3f}    gini(levels) = {gini(synced['size']):.3f}   "
      f"(0 = even, 1 = one book holds everything)")
    w("\n  heaviest books:")
    for i in order[:10]:
        w(f"    {synced['label'][i]:<20} size {synced['size'][i]:>7,.0f}  "
          f"rate {synced['rate'][i]:>6.3f}/s  -> {synced['work'][i]:>10,.0f} lv/s  "
          f"({synced['work'][i] / total_work * 100:.1f}% of fleet work)")

    # ---- shards -------------------------------------------------------------------------
    w("\n[7] SHARD BALANCE  (compare levelVisitsPerSecond, NOT books)")
    shards = a["byShard"]
    hdr = f"  {'shard':<7} {'books':>7} {'totalLevels':>13} {'updates/s':>11} {'levelVisits/s':>15} " \
          f"{'sampleAge':>10}  largest book"
    w(hdr)
    w("  " + "-" * (len(hdr) - 2))
    for s in shards:
        w(f"  {s['shard']:<7} {s['books']:>7,} {s['totalLevels']:>13,} "
          f"{s['updatesPerSecond']:>11,.1f} {s['levelVisitsPerSecond']:>15,.0f} "
          f"{s['sampleAgeMs']:>9} ms  {s['largestBook'] or '—'} ({s['largestBookSize']:,})")
    for key, label in (("books", "book count"), ("totalLevels", "levels"),
                       ("levelVisitsPerSecond", "level visits/s")):
        v = np.array([s[key] for s in shards], dtype=float)
        if v.min() > 0:
            w(f"  max/min {label:<16}: {v.max() / v.min():.3f}x")

    # ---- staleness / health -------------------------------------------------------------
    w("\n[8] STALENESS AND SYNC HEALTH")
    idle = synced["idleMs"]
    w(f"  idleMs over SYNCED books: median {np.median(idle) / 1000:.0f}s, "
      f"p75 {np.percentile(idle, 75) / 1000:.0f}s, p90 {np.percentile(idle, 90) / 1000:.0f}s, "
      f"p99 {np.percentile(idle, 99) / 1000:.0f}s, max {idle.max() / 1000:.0f}s")
    for thr in (30, 60, 120, 300, 600):
        n = (idle > thr * 1000).sum()
        w(f"    idle > {thr:>4}s : {n:>4} books ({n / len(idle) * 100:>5.1f}%)")
    quiet = np.argsort(idle)[::-1][:10]
    w("  quietest books:")
    for i in quiet:
        w(f"    {synced['label'][i]:<20} idle {idle[i] / 1000:>7.0f}s  size {synced['size'][i]:>7,.0f}  "
          f"rate {synced['rate'][i]:.4f}/s")

    unsynced = sub(t, t["state"] != "SYNCED")
    if len(unsynced["symbol"]):
        w(f"\n  {len(unsynced['symbol'])} books not SYNCED:")
        for st in ("SNAPSHOT_REQUESTED", "PENDING"):
            names = unsynced["label"][unsynced["state"] == st]
            if len(names):
                shown = ", ".join(names[:25]) + (" ..." if len(names) > 25 else "")
                w(f"    {st:<20} ({len(names)}): {shown}")

    r = t["resyncs"]
    w(f"\n  resyncs: {r.sum():,.0f} total across {int((r > 0).sum())} books "
      f"({(r > 0).sum() / len(r) * 100:.1f}% of the fleet); max on one book {r.max():.0f}")
    if live.any():
        hours = np.median(t["updates"][live] / t["rate"][live]) / 3600
        if hours > 0:
            w(f"  implied resync rate: {r.sum() / hours:.1f} resyncs/hour fleet-wide "
              f"= {r.sum() / hours / len(r) * 24:.2f} per book per day")

    # ---- outliers -----------------------------------------------------------------------
    o = d["sizeOutliers"]
    w("\n[9] SIZE OUTLIERS  (Tukey fences over the SYNCED pooled sample)")
    w(f"  fences [{fmt(o['lowerFence'], 0)}, {fmt(o['upperFence'], 0)}]   "
      f"extreme [{fmt(o['extremeLowerFence'], 0)}, {fmt(o['extremeUpperFence'], 0)}]")
    w(f"  {o['count']} outliers ({o['fraction'] * 100:.1f}%) = {o['lowCount']} low + "
      f"{o['highCount']} high; {o['extremeCount']} extreme")
    if o["high"]:
        w("  largest: " + ", ".join(f"{b['symbol']}/{b['market'][:3]} {b['size']:,}" for b in o["high"]))
    if o["low"]:
        w("  smallest: " + ", ".join(f"{b['symbol']}/{b['market'][:3]} {b['size']:,}" for b in o["low"]))

    # ---- capacity extrapolation ---------------------------------------------------------
    w("\n[10] CAPACITY EXTRAPOLATION")
    n_books = totals["books"]
    if n_books:
        w(f"  per book today: {totals['totalLevels'] / n_books:,.0f} levels, "
          f"{totals['levelVisitsPerSecond'] / n_books:,.0f} level visits/s, "
          f"{totals['usedHeapBytes'] / n_books / 1e6:.2f} MB heap")
        for mult, label in ((2, "2x books"), (3, "3x books (2nd exchange)"), (5, "5x books")):
            w(f"    {label:<26}: {totals['totalLevels'] * mult / 1e6:>7.2f} M levels, "
              f"{totals['levelVisitsPerSecond'] * mult / 1e6:>6.2f} M level visits/s, "
              f"{totals['usedHeapBytes'] * mult / 1e9:>6.2f} GB heap "
              f"({totals['usedHeapBytes'] * mult / totals['maxHeapBytes'] * 100:>5.0f}% of current -Xmx)")
    per_shard = totals["levelVisitsPerSecond"] / max(len(shards), 1)
    w(f"  current level visits/s per consumer thread: {per_shard:,.0f}")

    return "\n".join(out)


# ============================================================================================

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default=BASE_URL)
    ap.add_argument("--email", default=ADMIN_EMAIL)
    ap.add_argument("--password", default=ADMIN_PASSWORD)
    ap.add_argument("--out-dir", default="monitoring-analysis",
                    help="where the PNGs, the raw JSON and the report land")
    ap.add_argument("--raw-dir", default=None,
                    help="re-analyse a previously saved raw-monitoring.json instead of fetching")
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)

    if args.raw_dir:
        with open(os.path.join(args.raw_dir, "raw-monitoring.json"), encoding="utf-8") as fh:
            payload = json.load(fh)
        print(f"loaded cached capture from {args.raw_dir}")
    else:
        payload = fetch_all(args.base_url, args.email, args.password, args.out_dir)

    t = build_table(payload["books"])

    for path in (figure_sizes(payload, t, args.out_dir),
                 figure_work(payload, t, args.out_dir),
                 figure_health(payload, t, args.out_dir)):
        print(f"wrote {path}")

    text = report(payload, t)
    report_path = os.path.join(args.out_dir, "report.txt")
    with open(report_path, "w", encoding="utf-8") as fh:
        fh.write(text + "\n")
    print(f"wrote {report_path}\n")
    print(text)


if __name__ == "__main__":
    main()
