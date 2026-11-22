# An allowance of requests per client

> Checked against `b5b9f78` on 2026-11-22.

## The problem
One client can send requests faster than the server can answer them. The server then spends its time on that client,
and everyone else waits. Requests also differ in cost: some will take far more work than others.

## What we do
Each client gets an allowance of 120 units. It fills back up steadily, at 120 units a minute (two a second). A request
under `/v1/` spends units before the server checks who is asking and before it does the work: one unit today, more
for expensive requests later. When the allowance cannot cover a request, the server does no work. It answers
"too many requests" (HTTP status 429) and says how many seconds to wait.

A client with an anonymous session is counted twice: once by its session and once by its network address. A request
must fit in both allowances.

The count is kept outside the server process, in a store that every running copy of the server shares. Otherwise each
copy would give the same client its own allowance.

If that store does not answer within 250 ms, the request is let through. The limiter must never be the reason the
API is down.

## Its name
- The technique is a **token bucket**: the allowance is the bucket, a unit is a token.
- The library that does the counting is **Bucket4j**.
- The shared store is **Redis**: an in-memory key-value store that runs as its own process. Think of one map that
  several programs read and write over the network.
- Letting requests through when the store is down is called **failing open**.

## In your world
The nearest thing is throttling: "at most N events per period", as you would do for a search field. Two things
differ. The count is per client, and several processes share it, so many requests compete for one count.

## Here
- The check on each request:
  [RateLimitFilter.kt](../../services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RateLimitFilter.kt)
- The counting in Redis and the failing-open rule:
  [RedisRateLimiter.kt](../../services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RedisRateLimiter.kt)
- The values: `tastebuds.rate-limit` in
  [application.yaml](../../services/core/src/main/resources/application.yaml). 120 tokens, refilled over one minute,
  for the address and for the session. Timeout 250 ms.
- The cost of a request: `rules` in the same block. Today there is one rule: `GET /v1/meta` costs 1.

## Rejected
Nothing recorded for the technique or for the shared store. [Task 001](../tasks/001-rate-limiting.md) gives the
reason for the store: limits must hold across server copies.

Note, not from the record: the simpler option keeps the count in each server's memory. It needs no Redis and has no
failing-open case. It is wrong as soon as two copies run, because a client then gets one allowance per copy.

## Wrong if
- The service runs as a single copy for good. The in-memory version would then do.
- Abuse during a Redis outage becomes a real threat. Failing open means no limit at all for that time (backlog
  [2026-11-21-rate-limiter-redis-wide-overload-fails-open](../backlog/2026-11-21-rate-limiter-redis-wide-overload-fails-open.md)).
- Honest clients get a 429 in normal use. The limits are then too low for how the app really calls the API.

## Teach-back
Two copies of the server run and Redis is switched off. What happens to a client that sends 1,000 requests a minute,
and why was that accepted?
