# Binance API Rate Limits — Empirically Tested

> **Empirically tested** on 2026-10-02 (~10:08–10:13 UTC) from a single residential IP, against
> spot `GET https://api.binance.com/api/v1/depth?symbol=BTCUSDT&limit=1000` and futures
> `GET https://fapi.binance.com/fapi/v1/depth?symbol=BTCUSDT&limit=1000`. Requests were sent in
> parallel batches (10–40 concurrent `curl`s per batch) and each run stopped at the first batch
> containing a 4xx. Results reflect one IP, one region's CloudFront edges (`FRA56`, `WAW51`), and
> one time window — treat counter behaviour as approximate. The response shapes are exact.

Documented budgets going in: spot **6000 weight / minute**, `limit=1000` snapshot = **50 weight**;
futures `limit=1000` snapshot = **20 weight**.

## Spot — `GET /api/v1/depth` (`api.binance.com`)

### Runs

| Run | Requests | Duration | Outcome |
|---|---|---|---|
| Batches of 10 | 160 | ~35s (10:08:25–10:09:00) | all 200; max header weight 3250, then reset at the minute boundary |
| Batches of 40 | 600 | ~104s (10:11:11–10:12:55) | 588 × 200, 12 × 429; first 429 at request #577 |

### Rejection response

```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json;charset=UTF-8
x-mbx-uuid: a5554984-6051-45c1-ada1-45d553aec36c
retry-after: 5
x-mbx-used-weight: 6050
x-mbx-used-weight-1m: 6050
X-Cache: Error from cloudfront

{"code":-1003,"msg":"Too much request weight used; current limit is 6000 request weight per 1 MINUTE. Please use WebSocket Streams for live updates to avoid polling the API."}
```

### Findings

1. **The 6000 weight / minute limit is confirmed.** The first 429 carried `x-mbx-used-weight-1m: 6050`,
   i.e. it fired on the first request that pushed the counter past 6000.
2. **Snapshot cost is confirmed at 50 weight** — the header advances in steps of 50.
3. **Status is HTTP 429, body code `-1003`.** `retry-after` is present, in **seconds** (5 here), and
   was identical on all 12 rejections in the run.
4. Successful and rejected responses both carry **both** `x-mbx-used-weight` and
   `x-mbx-used-weight-1m` (same value).
5. The window is a **fixed calendar minute**: the counter dropped from ~3250 to 50 exactly at
   `10:09:00`.

## Futures — `GET /fapi/v1/depth` (`fapi.binance.com`)

### Runs

| Run | Requests | Duration | Outcome |
|---|---|---|---|
| Batches of 25 | 500 | ~89s (10:09:23–10:10:52) | 489 × 200, 11 × 429; first 429 at request #484 |

### Rejection response

```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
retry-after: 8
x-mbx-used-weight-1m: 2420
x-response-time: 0ms
X-Cache: Error from cloudfront

{"code":-1003,"msg":"Too many requests; current limit of IP(<client IP>) is 2400 requests per minute. Please use the websocket for live updates to avoid polling the API."}
```

### Findings

1. **The enforced futures limit is 2400 weight / minute, not 3000.** The first 429 carried
   `x-mbx-used-weight-1m: 2420` (first request past 2400), and the body states 2400 explicitly.
   Budget configuration must use 2400.
2. **Snapshot cost is confirmed at 20 weight** — the header advances in steps of 20.
3. **Status is HTTP 429, body code `-1003`** — same code as spot, but a different message. The message
   says "requests per minute" and echoes the client IP; the number is in fact a weight limit.
4. `retry-after` is present, in **seconds** (8 here), identical on all 11 rejections.
5. Futures responses carry **only `x-mbx-used-weight-1m`** — there is no bare `x-mbx-used-weight`
   header (unlike spot). Parse the `-1m` header on both venues.

## Cross-venue findings (both spot and futures)

1. **The used-weight header is not a single consistent counter under concurrency.** Responses in the
   same parallel batch reported 2–3 distinct counter "tracks" at once, e.g. spot `3800 / 5100 / 6050`
   and futures `~1400 / ~2400`. The likely explanation is per-gateway-node counters that converge with
   a lag (not verified). Consequences observed:
   - After the first 429, **roughly half of the requests in the same batch still returned 200**
     (served by a node whose counter was still below the limit).
   - Total accepted throughput was well above the nominal budget: futures accepted 483 × 20 =
     **~9,660 weight in ~89s**, spot 576 × 50 = **~28,800 weight in ~104s**. **Do not plan around
     this headroom** — it depends on load-balancer luck and can vanish.
   - A single response's header is therefore **not an exact view of the budget**. Treat it as a lower
     bound / correction signal, and keep an authoritative client-side count with a safety margin.
2. **Rejected requests still increase the counter.** The header kept climbing on 429 responses
   (spot 6050 → 6600, futures 2420 → 2620). Retrying while limited makes it worse; per Binance docs,
   continuing to hammer after 429s escalates to **HTTP 418** (IP ban, 2 min → 3 days).
3. **On any 429, back off the whole venue for at least `retry-after` seconds**, not just the failed
   request — the limit is per IP, and other in-flight snapshot fetches for that venue will also fail.
4. 429s come back through CloudFront (`X-Cache: Error from cloudfront`), but the body is Binance's
   standard `{"code":…,"msg":…}` JSON, so the normal error parsing path handles it.
5. One request in the first spot run failed at the transport level (curl returned no headers, no
   body) — a transient network failure, unrelated to rate limiting. Snapshot fetch code should treat
   "no response" separately from a 429.

## Not tested

- The **418** ban path and its escalating duration (deliberately avoided: every run stopped at the
  first 429 batch).
- Whether the per-node counter split also appears with sequential (non-concurrent) requests on a
  single keep-alive connection.
- Whether spot and futures share any budget (they use separate hosts and appear independent).
- Weight of other `limit` values (5 / 100 / 500 / 5000).
