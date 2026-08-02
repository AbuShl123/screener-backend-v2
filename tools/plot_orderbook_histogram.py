#!/usr/bin/env python3
"""
Plot the `histogram` block from GET /api/monitoring/orderbook.

The server-side histogram is FD-binned over the raw range, so a single 14k-level book stretches
the axis across 100 bins and leaves ~80 of them empty. This script draws three panels that each
work around that in a different way:

  1. the raw server histogram, as returned;
  2. the same data clipped to a percentile (default p99) — where the fleet actually lives;
  3. a log-x rebin from /orderbook/books, if you pass it — the honest view of a 3-decade spread.

Usage
-----
    pip install matplotlib

    # panels 1 and 2, from the fleet-stats response
    python plot_orderbook_histogram.py test.json

    # add panel 3 and the per-market split (needs the per-book table too)
    python plot_orderbook_histogram.py test.json --books books.json

    # fetch both first
    curl -H "Authorization: Bearer $TOKEN" \
         "http://localhost:8080/api/monitoring/orderbook?state=SYNCED" > test.json
    curl -H "Authorization: Bearer $TOKEN" \
         "http://localhost:8080/api/monitoring/orderbook/books?limit=2000&sort=SYMBOL" > books.json
"""

import argparse
import json
import sys

import matplotlib.pyplot as plt
import numpy as np


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def draw_server_histogram(ax, hist, stats, title):
    """Panel 1/2: bar chart straight from the server's buckets."""
    buckets = hist["buckets"]
    if not buckets:
        ax.text(0.5, 0.5, "empty sample", ha="center", va="center", transform=ax.transAxes)
        return

    lefts = [b["from"] for b in buckets]
    widths = [b["to"] - b["from"] for b in buckets]
    counts = [b["count"] for b in buckets]

    ax.bar(lefts, counts, width=widths, align="edge",
           color="#4c78a8", edgecolor="white", linewidth=0.3)

    # Reference lines: the eye needs them once the tail stretches the axis.
    for value, label, color, style in [
        (stats.get("median"), f"median {stats.get('median')}", "#e45756", "-"),
        (stats.get("mean"), f"mean {stats.get('mean')}", "#f58518", "--"),
        (stats.get("p95"), f"p95 {stats.get('p95')}", "#54a24b", ":"),
    ]:
        if value is not None:
            ax.axvline(value, color=color, linestyle=style, linewidth=1.4, label=label)

    ax.set_title(title, fontsize=10)
    ax.set_xlabel("book size (bids + asks)")
    ax.set_ylabel("books")
    ax.legend(fontsize=8, framealpha=0.9)
    ax.grid(axis="y", alpha=0.25)


def clip_buckets(hist, limit):
    """Re-emit the server's buckets truncated at `limit`, folding the rest into an overflow bar."""
    kept, overflow = [], 0
    for b in hist["buckets"]:
        if b["from"] < limit:
            kept.append(b)
        else:
            overflow += b["count"]
    return kept, overflow


def draw_clipped(ax, hist, stats, limit):
    kept, overflow = clip_buckets(hist, limit)
    if not kept:
        ax.text(0.5, 0.5, "nothing below the clip", ha="center", va="center", transform=ax.transAxes)
        return

    lefts = [b["from"] for b in kept]
    widths = [b["to"] - b["from"] for b in kept]
    counts = [b["count"] for b in kept]
    ax.bar(lefts, counts, width=widths, align="edge",
           color="#4c78a8", edgecolor="white", linewidth=0.3)

    for value, label, color, style in [
        (stats.get("median"), f"median {stats.get('median')}", "#e45756", "-"),
        (stats.get("mean"), f"mean {stats.get('mean')}", "#f58518", "--"),
    ]:
        if value is not None and value < limit:
            ax.axvline(value, color=color, linestyle=style, linewidth=1.4, label=label)

    shown = sum(counts)
    ax.set_title(f"clipped at {limit:.0f} — {shown} of {shown + overflow} books "
                 f"({overflow} above, off-scale)", fontsize=10)
    ax.set_xlabel("book size (bids + asks)")
    ax.set_ylabel("books")
    ax.legend(fontsize=8, framealpha=0.9)
    ax.grid(axis="y", alpha=0.25)


def draw_log_split(ax, rows):
    """Panel 3: log-x rebin of the raw per-book sizes, split by market."""
    spot = [r["size"] for r in rows if r["market"] == "SPOT" and r["size"] > 0]
    futures = [r["size"] for r in rows if r["market"] == "FUTURES" and r["size"] > 0]
    everything = spot + futures
    if not everything:
        ax.text(0.5, 0.5, "no books", ha="center", va="center", transform=ax.transAxes)
        return

    edges = np.logspace(np.log10(min(everything)), np.log10(max(everything)), 40)
    ax.hist([spot, futures], bins=edges, stacked=True,
            label=[f"SPOT (n={len(spot)})", f"FUTURES (n={len(futures)})"],
            color=["#72b7b2", "#4c78a8"], edgecolor="white", linewidth=0.3)

    ax.set_xscale("log")
    ax.set_title("log-x, split by market — the two populations `overall` pools", fontsize=10)
    ax.set_xlabel("book size (bids + asks), log scale")
    ax.set_ylabel("books")
    ax.legend(fontsize=8, framealpha=0.9)
    ax.grid(axis="y", alpha=0.25)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stats", help="JSON body of GET /api/monitoring/orderbook")
    ap.add_argument("--books", help="JSON body of GET /api/monitoring/orderbook/books (enables panel 3)")
    ap.add_argument("--clip", type=float, default=None,
                    help="clip panel 2 at this size (default: the sample's p95 x 1.5)")
    ap.add_argument("--out", help="write a PNG here instead of opening a window")
    args = ap.parse_args()

    stats_doc = load(args.stats)
    hist = stats_doc.get("histogram")
    overall = stats_doc.get("overall", {})
    if not hist:
        sys.exit("no `histogram` in that file — is it a fleet-stats response?")

    rows = None
    if args.books:
        rows = load(args.books).get("books", [])

    panels = 3 if rows else 2
    fig, axes = plt.subplots(panels, 1, figsize=(11, 3.6 * panels))

    totals = stats_doc.get("totals", {})
    fig.suptitle(
        f"Orderbook size distribution — {overall.get('n', '?')} books "
        f"({totals.get('byMarket', {}).get('SPOT', '?')} spot / "
        f"{totals.get('byMarket', {}).get('FUTURES', '?')} futures), "
        f"skewness {overall.get('skewness')} ({overall.get('shape')})",
        fontsize=12)

    draw_server_histogram(
        axes[0], hist, overall,
        f"server histogram as returned — {hist['bins']} bins, "
        f"{sum(1 for b in hist['buckets'] if b['count'] == 0)} empty, method {hist['method']}")

    clip = args.clip
    if clip is None:
        clip = (overall.get("p95") or overall.get("max") or 1) * 1.5
    draw_clipped(axes[1], hist, overall, clip)

    if rows:
        draw_log_split(axes[2], rows)

    fig.tight_layout(rect=(0, 0, 1, 0.96))

    if args.out:
        fig.savefig(args.out, dpi=140)
        print(f"wrote {args.out}")
    else:
        plt.show()


if __name__ == "__main__":
    main()
