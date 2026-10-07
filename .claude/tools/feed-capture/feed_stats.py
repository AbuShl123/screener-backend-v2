"""Per-venue noise metrics for a capture.mjs recording.

Usage: python feed_stats.py <capture.jsonl>

Prints, per venue: books in the initial SNAPSHOT, live DEPTH messages (and per second), drops,
distinct books touched, appearances lasting <= 0.5s, and the tier histogram of all live levels.
These are the metrics .claude/plans/mexc-feed-noise.md uses for its baseline and acceptance.
"""
import collections as C
import json
import sys

msgs = [json.loads(line) for line in open(sys.argv[1], encoding='utf-8')]
venue = lambda m: f"{m['exchange']}_{m['market']}"

snap = next(x['m'] for x in msgs if x['m']['type'] == 'SNAPSHOT')
snap_books = C.Counter(venue(e) for e in snap['data'])

live = [x for x in msgs if x['m']['type'] == 'DEPTH']
dur = max((live[-1]['t'] - live[0]['t']) / 1000, 1) if live else 1

count, drops, flashes = C.Counter(), C.Counter(), C.Counter()
books, tiers = C.defaultdict(set), C.defaultdict(C.Counter)
visible_since = {}
for x in live:
    m, v = x['m'], venue(x['m'])
    key = (v, m['symbol'])
    count[v] += 1
    books[v].add(m['symbol'])
    if m['data'] is None:
        drops[v] += 1
        since = visible_since.pop(key, None)
        if since is not None and x['t'] - since <= 500:
            flashes[v] += 1
        continue
    visible_since.setdefault(key, x['t'])
    for side in ('bids', 'asks'):
        for level in m['data'][side]:
            tiers[v][level[2]] += 1

print(f"capture: {dur:.0f}s live")
print(f"{'venue':16s} {'snapBooks':>9s} {'msgs':>6s} {'msg/s':>6s} {'drops':>6s} {'books':>6s} {'<=0.5s':>7s}  tiers")
for v in sorted(set(count) | set(snap_books)):
    print(f"{v:16s} {snap_books[v]:9d} {count[v]:6d} {count[v] / dur:6.1f} {drops[v]:6d} "
          f"{len(books[v]):6d} {flashes[v]:7d}  {dict(sorted(tiers[v].items()))}")
