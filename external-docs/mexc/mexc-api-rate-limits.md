# MEXC API Rate Limits — Order Book Snapshot

## Spot — `GET /api/v3/depth`

| Item | Value |
|---|---|
| Weight per request | 3 (IP-based), flat regardless of `limit` (100 default, 5000 max) |
| Budget | 300 weight / 10s per IP (shared with other IP-weighted endpoints) |
| Max requests | `300 / 3` = **100 req / 10s ≈ 10 req/s** per IP |
| Account (UID) budget | 500 weight / 10s, separate pool for signed endpoints |

## Futures — `GET /api/v1/contract/depth/{symbol}`

| Item | Value |
|---|---|
| Limit | **10 requests / 2 seconds (5 req/s)** |
| Model | Fixed count, not weight-based |
| Scope | Not stated in docs (assume per IP, public endpoint) |

## What Happens on Violation

| Stage | Response |
|---|---|
| Limit exceeded | HTTP **429** — back off, not yet banned |
| Keep exceeding after 429 | IP ban → HTTP **418** |
| Ban duration | Escalates **2 minutes → 3 days** for repeat offenders |
| Retry-After header | Sent on both 429 (seconds to avoid ban) and 418 (seconds until ban ends) |
| WAF violation | HTTP 403 (separate from rate limiting) |

**Note:** MEXC's FAQ separately states access resumes "after 10 minutes," which conflicts with the 2-min–3-day escalation described in the same docs. Treat the longer figure as authoritative and always honor `Retry-After`.

Futures docs state only the request-count limit; no ban escalation policy is documented for futures specifically.

## Sources
- https://www.mexc.com/api-docs/spot-v3/introduction
- https://www.mexc.com/api-docs/spot-v3/market-data-endpoints/order-book
- https://www.mexc.com/api-docs/futures/market-endpoints/get-contract-order-book-depth