"""Replay one ticker's order book from the MEXC depth dumps in the terminal.

Each frame is one 15s snapshot: asks on top (highest price first), the spread
with mid price in the middle, bids underneath. Only levels within MAX_DISTANCE_PCT
of mid are shown; if a side still has more than MAX_ROWS_PER_SIDE levels, the
smallest-notional ones are hidden (the count is printed).

Change markers compare each level with the previous frame:
  new  level appeared    ▲  notional grew    ▼  notional shrank

Run:  python .claude/tools/depth-analysis/book_replay.py
"""
import gzip
import os
import sys
import time

from datetime import datetime
from pathlib import Path

# ---- configuration ---------------------------------------------------------
TICKER = "DOGEUSDT"
MAX_DISTANCE_PCT = 1.0      # show levels within this % of mid
MAX_ROWS_PER_SIDE = 100      # keep the N largest-notional levels per side
FRAME_DELAY_S = 0         # seconds between frames; 0 = press Enter to advance
START_FRAME = 41             # skip ahead in the recording
DUMP_DIR = None             # None = latest run under <repo>/depth-dumps
BAR_WIDTH = 40              # bar of the frame's largest shown level
# ----------------------------------------------------------------------------

RED, GREEN, DIM, BOLD, RESET = "\033[31m", "\033[32m", "\033[2m", "\033[1m", "\033[0m"


def find_dump_dir():
    if DUMP_DIR:
        return Path(DUMP_DIR)
    root = Path(__file__).resolve().parents[3] / "depth-dumps"
    runs = sorted(p for p in root.iterdir() if p.is_dir())
    if not runs:
        sys.exit(f"no dump runs under {root}")
    return runs[-1]


def load_frames(dump_dir, ticker):
    """Returns [(capture_ms, bids{price: qty}, asks{price: qty})] in time order."""
    files = sorted(dump_dir.glob("depth-*.csv.gz"), key=lambda p: int(p.name[6:-7]))
    needle = f",{ticker},".encode()
    frames = []
    for path in files:
        bids, asks, capture_ms = {}, {}, None
        data = gzip.decompress(path.read_bytes())
        # rows are grouped by symbol: parse only the span between the first and last match
        first, last = data.find(needle), data.rfind(needle)
        if first < 0:
            continue
        start = data.rfind(b"\n", 0, first) + 1
        end = data.find(b"\n", last)
        for line in data[start:end if end >= 0 else None].split(b"\n"):
            if needle not in line:
                continue
            ms, _, side, price, qty, _ = line.split(b",", 5)
            capture_ms = int(ms)
            (bids if side == b"B" else asks)[float(price)] = float(qty)
        if bids and asks:
            frames.append((capture_ms, bids, asks))
    return frames


def window(levels, lo, hi):
    """Levels inside [lo, hi], trimmed to the largest MAX_ROWS_PER_SIDE by notional."""
    inside = {p: q for p, q in levels.items() if lo <= p <= hi}
    if len(inside) <= MAX_ROWS_PER_SIDE:
        return inside, 0
    keep = sorted(inside, key=lambda p: p * inside[p], reverse=True)[:MAX_ROWS_PER_SIDE]
    return {p: inside[p] for p in keep}, len(inside) - MAX_ROWS_PER_SIDE


def price_decimals(frames):
    decimals = 0
    for _, bids, asks in frames[:5]:
        for p in list(bids)[:50] + list(asks)[:50]:
            s = f"{p:.10f}".rstrip("0")
            decimals = max(decimals, len(s) - s.index(".") - 1)
    return decimals


def fmt_notional(n):
    for div, suffix in ((1e9, "B"), (1e6, "M"), (1e3, "K")):
        if n >= div:
            return f"${n / div:,.2f}{suffix}"
    return f"${n:,.0f}"


def marker(price, qty, prev_side):
    if prev_side is None:
        return "   "
    old = prev_side.get(price)
    if old is None:
        return "new"
    if qty > old:
        return " ▲ "
    if qty < old:
        return " ▼ "
    return "   "


def render(frame_no, total, frame, prev, decimals):
    capture_ms, bids, asks = frame
    best_bid, best_ask = max(bids), min(asks)
    mid = (best_bid + best_ask) / 2
    lo, hi = mid * (1 - MAX_DISTANCE_PCT / 100), mid * (1 + MAX_DISTANCE_PCT / 100)
    shown_asks, hidden_asks = window(asks, mid, hi)
    shown_bids, hidden_bids = window(bids, lo, mid)
    prev_bids, prev_asks = (prev[1], prev[2]) if prev else (None, None)

    ts = datetime.fromtimestamp(capture_ms / 1000).strftime("%Y-%m-%d %H:%M:%S")
    out = [f"{BOLD}{TICKER}{RESET}  {ts}  frame {frame_no + 1}/{total}  "
           f"(±{MAX_DISTANCE_PCT}% of mid, ≤{MAX_ROWS_PER_SIDE} rows/side)", ""]
    out.append(f"{'price':>{decimals + 8}}  {'notional':>11}")

    bar_scale = max(p * q for side in (shown_asks, shown_bids) for p, q in side.items())

    def row(price, qty, colour, prev_side):
        notional = price * qty
        bar = "█" * max(1, round(notional / bar_scale * BAR_WIDTH))
        return (f"{colour}{price:>{decimals + 8}.{decimals}f}  {fmt_notional(notional):>11}{RESET} "
                f"{marker(price, qty, prev_side)} {colour}{bar}{RESET}")

    if hidden_asks:
        out.append(f"{DIM}  … {hidden_asks} smaller asks hidden{RESET}")
    for p in sorted(shown_asks, reverse=True):
        out.append(row(p, shown_asks[p], RED, prev_asks))
    spread = best_ask - best_bid
    out.append(f"{BOLD}  ── mid {mid:.{decimals + 1}f}   spread {spread:.{decimals}f} "
               f"({spread / mid * 100:.4f}%) ──{RESET}")
    for p in sorted(shown_bids, reverse=True):
        out.append(row(p, shown_bids[p], GREEN, prev_bids))
    if hidden_bids:
        out.append(f"{DIM}  … {hidden_bids} smaller bids hidden{RESET}")
    return "\n".join(out)



def main():
    if sys.platform == "win32":
        os.system("")  # enable ANSI escape handling in the Windows console
    sys.stdout.reconfigure(encoding="utf-8")

    dump_dir = find_dump_dir()
    print(f"loading {TICKER} from {dump_dir} …")
    frames = load_frames(dump_dir, TICKER)
    if not frames:
        sys.exit(f"no synced snapshots of {TICKER} in {dump_dir}")
    decimals = price_decimals(frames)

    prev = frames[START_FRAME - 1] if START_FRAME > 0 else None
    try:
        for i in range(START_FRAME, len(frames)):
            sys.stdout.write("\033[H\033[2J" + render(i, len(frames), frames[i], prev, decimals) + "\n")
            sys.stdout.flush()
            prev = frames[i]
            if FRAME_DELAY_S > 0:
                time.sleep(FRAME_DELAY_S)
            else:
                input(f"{DIM}Enter = next frame, Ctrl+C = quit{RESET}")
    except (KeyboardInterrupt, EOFError):
        print()


if __name__ == "__main__":
    main()
