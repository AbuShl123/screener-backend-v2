"""
Shared loader for the orderbook monitoring CSV capture.

Reads `books-*.csv`, `fleet-*.csv` and `shards-*.csv` as written by
`monitoring/OrderBookCsvRecorder`. See `.claude/docs/orderbook-monitoring.md` §8 for
the column reference and §8.5 for the restart / gap / warm-up caveats this module
implements.

Everything here is deliberately restart-safe: cumulative counters (`updates`,
`resyncs`) are since-JVM-start, so their diffs are masked to NaN wherever they step
backwards rather than clipped to zero.
"""

from __future__ import annotations

import glob
import os

import numpy as np
import pandas as pd

DEFAULT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "monitoring-data",
    "monitoring-data",
)


def load(prefix: str, directory: str = DEFAULT_DIR) -> pd.DataFrame:
    """Concatenate every `<prefix>-<date>.csv` in `directory`, sorted by time."""
    paths = sorted(glob.glob(os.path.join(directory, f"{prefix}-*.csv")))
    if not paths:
        raise FileNotFoundError(f"no {prefix}-*.csv under {directory}")
    frames = [
        pd.read_csv(p, parse_dates=["at"], encoding="utf-8", on_bad_lines="warn")
        for p in paths
    ]
    df = pd.concat(frames, ignore_index=True)
    return df.sort_values("at").reset_index(drop=True)


def load_all(directory: str = DEFAULT_DIR):
    return load("books", directory), load("fleet", directory), load("shards", directory)


def add_measured_rate(books: pd.DataFrame) -> pd.DataFrame:
    """Per-interval update rate from the cumulative counter, independent of the EWMA.

    NaN across a restart (negative delta) and on each book's first observation.
    """
    books = books.sort_values(["symbol", "market", "at"]).copy()
    g = books.groupby(["symbol", "market"], sort=False)
    d_updates = g["updates"].diff()
    d_resyncs = g["resyncs"].diff()
    dt = g["at"].diff().dt.total_seconds()
    books["dt"] = dt
    books["d_updates"] = d_updates.where(d_updates >= 0)
    books["d_resyncs"] = d_resyncs.where(d_resyncs >= 0)
    books["rate_measured"] = books["d_updates"] / dt
    return books.sort_values("at").reset_index(drop=True)


def detect_restarts(books: pd.DataFrame) -> pd.DataFrame:
    """Ticks at which any book's cumulative `updates` stepped backwards."""
    b = books.sort_values(["symbol", "market", "at"])
    g = b.groupby(["symbol", "market"], sort=False)
    back = g["updates"].diff() < 0
    return b.loc[back, ["at", "symbol", "market", "updates"]]


def tick_grid(df: pd.DataFrame, expected_seconds: float) -> pd.DataFrame:
    """Gap report: consecutive tick timestamps whose spacing exceeds 1.5x expected."""
    ticks = pd.Index(sorted(df["at"].unique()))
    gaps = ticks.to_series().diff().dt.total_seconds()
    bad = gaps[gaps > expected_seconds * 1.5]
    return pd.DataFrame({"at": bad.index, "gap_seconds": bad.values})


def hours_since_start(s: pd.Series) -> pd.Series:
    return (s - s.min()).dt.total_seconds() / 3600.0


def lower_envelope(series: pd.Series, window: str = "10min") -> pd.Series:
    """Rolling minimum — the live-set estimate under a GC sawtooth."""
    return series.rolling(window).min()


def gini(x) -> float:
    x = np.sort(np.asarray(x, dtype=float))
    x = x[~np.isnan(x)]
    n = x.size
    if n == 0 or x.sum() == 0:
        return float("nan")
    idx = np.arange(1, n + 1)
    return float((2 * idx - n - 1).dot(x) / (n * x.sum()))
