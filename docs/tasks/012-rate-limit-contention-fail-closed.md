# 012 — Parallel requests on one bucket never fail open (rate limiter contention)

- **Status:** READY
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-21
- **Depends on:** none

## Goal
Close a rate-limit bypass found by the spec audit of 2026-11-21 (part B, B1; security). Bucket4j's compare-and-swap
(CAS) loop has no retry cap: parallel requests on one bucket key lose CAS races until the 250 ms deadline,
`RedisRateLimiter` maps the resulting `io.github.bucket4j.TimeoutException` to `Unavailable`, and `RateLimitFilter`
fails open. One client with enough parallel requests bypasses its own IP or session bucket (measured with a harness
that mirrors the code on `origin/main`: 1000 parallel requests on a bucket of 120 → 35–41 allowed, 959–965 failed
open, all passed). After this task,
contention on one key ends in a 429 and never fails open, while a Redis outage (no connection, dropped connection, no
answer on an open connection) still fails open (task 001: "an outage never takes the API down"). The task also takes
over audit finding D3: the fail-open tests name the failure that fails open, and a stall on an open connection is
tested. Implements docs/PLAN.md §8.2 (per-client buckets) without changing a §2 decision.

## Non-goals
- Audit finding F1 (a request with a session waits up to 2 × `timeout`: session call, then IP call). It goes to the
  backlog; this task must not make it worse (no call waits longer than today, see Limits).
- Redis-wide overload: many keys (many IPv6 /64s or many sessions) with thousands of parallel requests per pod can
  still slow Redis past the deadline, and then every key fails open. This task bounds one key's share; the overload
  case needs a per-client concurrency limit at the ingress or a later limiter task (backlog candidate, §7).
- Giving tokens back to the session bucket when the IP bucket rejects or is contended
  (`docs/backlog/2026-11-21-rate-limit-no-refund-on-later-reject.md`).
- New configuration properties for the two limits (constants for now), waiting for a slot, per-key locks, Lua-based
  bucket logic, a different Bucket4j strategy, or any new dependency.
- Bucket key names, TTL, `tastebuds.rate-limit.*` properties and validation, `LazyConnection`, Lettuce client options,
  the filter position, the session-then-IP order, and "an `Unavailable` session call skips the IP bucket" (task 007).
- `ClientKey.toString()` (`docs/backlog/2026-11-21-client-key-tostring-leaks-identity.md`).
- Audit part D findings other than D3 (task 014), `application.yaml`, `deploy/**`.

## Allowed paths
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/KeyInFlightLimit.kt`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RedisRateLimiter.kt`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RateLimitFilter.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/KeyInFlightLimitTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RedisRateLimiterContentionTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RedisRateLimiterFailOpenTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/StallingTcpProxy.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RateLimitFilterTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/RateLimitIntegrationTest.kt`
- `docs/observability/WIDE_EVENT_SCHEMA.md`
- `SECURITY.md`

## Design notes

### 1. Decision
Two client-side bounds, both in the limiter, no waiting anywhere:
1. **In-flight cap per key (new `KeyInFlightLimit`).** At most `MAX_IN_FLIGHT_PER_KEY = 16` bucket operations for one
   bucket key run at once in one JVM. A call beyond that returns contention at once, without a Redis call. This keeps
   one key from queueing thousands of commands on the shared Lettuce connection, which is what turns contention into a
   timeout even with a retry cap.
2. **Retry cap (Bucket4j `maxRetries`).** `MAX_CAS_ATTEMPTS = 32` CAS attempts per operation. When all are lost,
   Bucket4j throws its retry-exhaustion error, which the limiter maps to contention.

Contention becomes `RateLimitDecision.Rejected(remaining = null, retryAfterSeconds = 1, reason = CONTENTION)`: a 429
with `Retry-After: 1`. Every other exception stays `Unavailable` (fail open).

Why these two (all numbers measured, see "Assumptions verified"):
- A lost CAS attempt proves that Redis answered and another writer of the *same key* won. Only requests with the same
  bucket key write that key, so contention is always the key's own load and is safe to reject.
- The retry cap alone does not close the bypass: 1000 parallel requests on one key still failed open 964–973 times
  (the shared connection congests, the deadline fires before 32 attempts end). With the in-flight cap added:
  200 / 1000 / 2000 / 5000 parallel requests → 0 failed open, 16–20 allowed, slowest call ≤ 12 ms.
- Count-based cap, not time-based: each lost attempt means that another write of the same key committed while the
  operation ran, so an operation is rejected for contention only after 32 other requests of its own key committed
  during it. Within one deadline (250 ms) that takes more than 128 requests per second on the key, however slow Redis
  is (measured: 16 parallel callers, or 2 instances × 16, are all allowed). A time budget (for example "stop retrying
  after `timeout`/2") would reject a client's third parallel request whenever *someone else* slows Redis down
  (rule 7), so it is not used.
- Rejected alternatives: a per-key lock with waiting (a waiter needs its own Redis call after the wait, so one call
  could take wait + deadline ≈ 2 × `timeout`, making F1 worse, and a waiter that times out cannot tell a long queue
  from a Redis stall); mapping `TimeoutException` to 429 (an outage or a Redis-wide overload would reject every
  client); a small `maxRetries` without the cap (`maxRetries(1)`: 20 parallel requests → 1–2 allowed; one CAS winner
  per round rejects normal bursts).
- Cost, accepted: more than 16 truly parallel requests of one client on one pod get 429 for the excess even with
  tokens left (measured: 200 parallel on a full bucket → 16 allowed, 184 contention). The default bucket allows 120 per
  minute and app clients send a handful of parallel requests per page; the wide event field
  `rate_limit.reject_reason=contention` makes the effect visible if the limit is too low.

### 2. `platform/ratelimit/KeyInFlightLimit.kt` (new)
```kotlin
package dev.danya.tastebuds.core.platform.ratelimit

import java.util.concurrent.ConcurrentHashMap

/**
 * Caps the bucket operations that run at once for one key in this JVM. Calls beyond the cap are turned away at once,
 * without touching Redis, so one client's parallel requests can neither spin in compare-and-swap retries nor queue
 * on the shared connection. Keys are bucket keys (client identity): never log them.
 */
internal class KeyInFlightLimit(
    private val maxPerKey: Int,
) {
    private val inFlight = ConcurrentHashMap<String, Int>()

    init {
        require(maxPerKey >= 1) { "maxPerKey must be at least 1" }
    }

    /** Runs [block] if fewer than [maxPerKey] calls for [key] are running; otherwise returns null at once. */
    fun <T : Any> tryRun(
        key: String,
        block: () -> T,
    ): T? {
        var admitted = false
        inFlight.compute(key) { _, count ->
            val current = count ?: 0
            if (current < maxPerKey) {
                admitted = true
                current + 1
            } else {
                current
            }
        }
        if (!admitted) return null
        try {
            return block()
        } finally {
            inFlight.compute(key) { _, count -> if (count == null || count <= 1) null else count - 1 }
        }
    }

    /** Number of keys with calls in flight; for tests. */
    fun trackedKeys(): Int = inFlight.size
}
```
- `compute` holds the bin lock only for the counter update (no I/O, no blocking inside), so the short carrier pinning
  on Java 21 does not matter. Never call `block` inside `compute`.
- An entry exists only while a call for its key runs; the `finally` removes it at 0, also when `block` throws.

### 3. `platform/ratelimit/RedisRateLimiter.kt`
Add at the top level (after the imports), and use them in `RedisRateLimiter` and `RateLimitRedis`:
```kotlin
/** Bucket operations for one key that may run at once in this JVM; more get contention without a Redis call. */
internal const val MAX_IN_FLIGHT_PER_KEY = 16

/** Compare-and-swap attempts per bucket operation (Bucket4j `maxRetries` counts attempts); all lost → contention. */
internal const val MAX_CAS_ATTEMPTS = 32

/** Why a bucket said no (`rate_limit.reject_reason`). */
internal enum class RejectReason {
    /** The bucket had fewer tokens than the request costs. */
    TOKENS,

    /**
     * Too many parallel requests on one bucket: [MAX_IN_FLIGHT_PER_KEY] operations for the key already run in this
     * JVM, or all [MAX_CAS_ATTEMPTS] compare-and-swap attempts lost to other writers of the same key. Only the key's
     * own requests cause it, so it is a 429 and never a fail open.
     */
    CONTENTION,
}
```
Change `RateLimitDecision.Rejected` (no default for `reason`):
```kotlin
    /** [remaining] is null for [RejectReason.CONTENTION]: the bucket was not read or not changed. */
    data class Rejected(
        val remaining: Long?,
        val retryAfterSeconds: Long,
        val reason: RejectReason,
    ) : RateLimitDecision
```
`RedisRateLimiter`:
- New field `private val inFlight = KeyInFlightLimit(MAX_IN_FLIGHT_PER_KEY)`. The constructor stays
  `(proxyManager, properties, nanoTime)`.
- `tryConsume` (order matters: no connection → `Unavailable` exactly as today, before the cap is asked):
  ```kotlin
      override fun tryConsume(
          key: ClientKey,
          cost: Long,
      ): RateLimitDecision =
          try {
              val manager = proxyManager() ?: return RateLimitDecision.Unavailable
              val isSession = key.type == ClientKeyType.SESSION
              val configuration = if (isSession) sessionConfiguration else ipConfiguration
              val bucketKey = (if (isSession) sessionPrefix else ipPrefix) + key.value
              val probe =
                  inFlight.tryRun(bucketKey) {
                      manager.builder().build(bucketKey) { configuration }.tryConsumeAndReturnRemaining(cost)
                  }
              when {
                  probe == null -> CONTENDED
                  probe.isConsumed -> RateLimitDecision.Allowed(probe.remainingTokens)
                  else ->
                      RateLimitDecision.Rejected(
                          probe.remainingTokens,
                          retryAfterSeconds(probe.nanosToWaitForRefill),
                          RejectReason.TOKENS,
                      )
              }
          } catch (
              // Store errors (no connection, no answer in time) fail open: rate limiting must never take the API down.
              // Lost compare-and-swap races are the key's own parallel requests: they never fail open.
              @Suppress("TooGenericExceptionCaught") e: Exception,
          ) {
              if (isRetryExhaustion(e)) {
                  CONTENDED
              } else {
                  warnThrottled(e)
                  RateLimitDecision.Unavailable
              }
          }
  ```
- New private function:
  ```kotlin
      /**
       * Bucket4j's "CAS operation failed after N retry attempts" (`BucketExceptions.maxRetriesExceeded`): Redis
       * answered every attempt and another writer of the same key won each time. Its subclass
       * `io.github.bucket4j.TimeoutException` (no answer in time) and wrappers with a cause are store errors.
       */
      private fun isRetryExhaustion(e: Exception): Boolean =
          e.javaClass == BucketExceptions.BucketExecutionException::class.java && e.cause == null
  ```
  The exact-class check is required: `io.github.bucket4j.TimeoutException` extends `BucketExecutionException` and also
  has no cause.
- Companion: `val CONTENDED = RateLimitDecision.Rejected(null, CONTENTION_RETRY_AFTER_SECONDS, RejectReason.CONTENTION)`
  and `const val CONTENTION_RETRY_AFTER_SECONDS = 1L`.
- Contention writes **no log line** and does not touch the WARN throttle (`lastWarn`): a client decides how often it
  happens. The request's wide event records it (§4).
- `RateLimitRedis.proxyManager`: add `.maxRetries(MAX_CAS_ATTEMPTS)` right after `.requestTimeout(timeout)`. Keep the
  signature `proxyManager(client, timeout)` and the signature of `RateLimitRedis.client` (the new contention test must
  compile against `origin/main`, AC 1).
- Bucket key prefixes (`bucketKeyPrefix`, `sessionBucketKeyPrefix`) stay unchanged, so deploying this resets no bucket.

### 4. `platform/ratelimit/RateLimitFilter.kt`
The `when` in `doFilterInternal` keeps its three branches and two `return`s; contention arrives as `Rejected`.
`reject(...)` changes to:
```kotlin
    private fun reject(
        request: HttpServletRequest,
        response: HttpServletResponse,
        key: ClientKey,
        decision: RateLimitDecision.Rejected,
    ) {
        val event = WideEvent.current()
        event
            ?.put("rate_limit.outcome", RateLimitOutcome.REJECTED)
            ?.put("rate_limit.limited_by", if (key.type == ClientKeyType.SESSION) "session" else "ip")
            ?.put("rate_limit.reject_reason", decision.reason)
        decision.remaining?.let { event?.put("rate_limit.remaining", it) }
        response.setHeader(HttpHeaders.RETRY_AFTER, decision.retryAfterSeconds.toString())
        ProblemResponses.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", "Rate limit exceeded.")
    }
```
The wide event writes enums as lowercase names (`Redaction.sanitize`), so the values are `tokens` and `contention`.
The 429 body is the same for both reasons (no hint about the cause to the client beyond `Retry-After`).

### 5. Docs
`docs/observability/WIDE_EVENT_SCHEMA.md`, section "Rate limiting": replace the rows `rate_limit.outcome` and
`rate_limit.remaining`, and add the `rate_limit.reject_reason` row after `rate_limit.limited_by`. Each row is one line
in the file (wrapped here only by the code block width):
```text
| `rate_limit.outcome` | string | `allowed`, `rejected` (429; `rate_limit.reject_reason` says why) or `unavailable` (Redis unreachable or too slow; the request went through) |
| `rate_limit.remaining` | long | Tokens left after this request in the bucket that decided: the smaller of the session and IP buckets when allowed, the rejecting bucket when rejected; absent when `unavailable` or when `reject_reason` is `contention` |
| `rate_limit.reject_reason` | string | Only when `rejected`: `tokens` (the bucket had fewer tokens than the cost) or `contention` (too many parallel requests on one bucket: 16 already in flight on this pod, or 32 lost compare-and-swap attempts; `Retry-After: 1`, the contended bucket was not charged) |
```

`SECURITY.md`, line 25 (rate limiting bullet, one line): replace its last sentence

```text
Fails open while Redis is unavailable
```
with
```text
Fails open while Redis is unavailable (unreachable, or no answer within `tastebuds.rate-limit.timeout`, 250 ms by default); parallel requests on one bucket never fail open: beyond 16 in flight on one pod, or after 32 lost compare-and-swap attempts, they get 429 with `Retry-After: 1`, also while Redis is unavailable
```
Task 013 edits `SECURITY.md` line 18 and `WIDE_EVENT_SCHEMA.md` line 74; these edits touch other lines.

### 6. Test helper `StallingTcpProxy.kt` (test source set)
```kotlin
/**
 * Loopback TCP proxy to [upstreamHost]:[upstreamPort] for fail-open tests. [stall] stops forwarding in both
 * directions and keeps every socket open (a Redis that hangs on an open connection); [close] closes every socket
 * (a dropped connection). Never pause the shared Redis container instead: other tests use it.
 */
internal class StallingTcpProxy(
    upstreamHost: String,
    upstreamPort: Int,
) : AutoCloseable {
    val port: Int
    fun stall()
    override fun close()
}
```
- `ServerSocket(0, 50, InetAddress.getLoopbackAddress())`; an accept loop on a virtual thread; for each accepted
  socket, open `Socket(upstreamHost, upstreamPort)` and start two pump threads (virtual), one per direction.
- A pump reads into an 8 KiB buffer and writes and flushes only while `@Volatile var stalled` is false; bytes read
  while stalled are dropped (a stalled proxy never resumes). An `IOException` ends the pump quietly (expected on
  `close`).
- Keep every socket in a `CopyOnWriteArrayList`; `close()` closes the server socket and all sockets.

### 7. What a client can make it do (SPEC_CHECKLIST rule 7)
| Fallback or new mapping | Triggers after this task | Can a client trigger it? |
|---|---|---|
| `Unavailable` → request passes (fail open) | No connection (`LazyConnection` connecting or in backoff); connect failure (`io.lettuce.core.RedisConnectionException`); dropped connection (`io.lettuce.core.RedisException`, Lettuce rejects commands while disconnected); no answer within the deadline (`io.github.bucket4j.TimeoutException`); any other exception except retry exhaustion | Not with one key: at most 16 operations of a key run per pod, so one key adds at most 16 outstanding commands to the connection (each synchronous call has one command outstanding); measured 5000 parallel requests on one key → 0 failed open, slowest 9 ms. Not with crafted input: the only client-derived input is the bucket key (validated IP / IPv6 prefix / signed session id, task 001 §3, 007), and the cost comes from configuration. **Residual (Non-goals):** Redis-wide overload from many keys; in a slow environment (one CAS attempt slower than ~7.8 ms, see Limits) contended calls hit the deadline first and fail open, at most 16 per key per pod at a time |
| New: contention → 429 | 16 operations for the same bucket key already in flight in this JVM, or 32 lost CAS attempts on the same Redis key | Only with the key's own requests: a client cannot produce another client's bucket key, and a CAS attempt is lost only to a write of the same key. Clients that already share a key (one NAT IPv4, one IPv6 /64, the shared `ip:invalid` key) share its bucket by design (task 001 §3), so one of them can already exhaust it for the others; the cap adds nothing new. Redis slowness caused by others does not reject a client: a call holds its slot for at most one deadline (250 ms), so a full cap needs more than 64 requests per second of the key on one pod (32 × the default refill rate), and 32 lost attempts need 32 commits of the key during one operation, more than 128 requests per second |
| Side effects | Contention writes no log line; store errors keep the existing WARN of at most one per 60 s per limiter; `KeyInFlightLimit` entries live only while calls run (memory bounded by in-flight requests) | No log or memory growth a client controls |

Backlog candidates for the orchestrator (not written by this task): (1) Redis-wide overload still fails the limiter
open — per-client in-flight limit at the ingress (Traefik, D21) or a limiter-wide bound; (2) measure the CAS attempt
time in the cluster once task 011 runs, and lower `MAX_CAS_ATTEMPTS` if one attempt exceeds ~7 ms.

### 8. Lifecycle (rule 6)
- `KeyInFlightLimit` entry: created by the first running call for a key, +1 per call, −1 when the call returns or
  throws, removed at 0. Per JVM, never shared between pods, gone on restart (nothing to clean up).
- A Lettuce command whose Bucket4j deadline fired stays pending until Lettuce's own command timeout (250 ms after it
  was sent) completes it; its in-flight slot was freed when the caller returned.
- Redis bucket keys and TTL: unchanged, so a deploy resets no bucket. During a rolling deploy, old pods still fail open
  under contention until replaced; old and new pods share buckets safely (the retry cap and the in-flight cap are
  client-side; the stored state format is unchanged). A rollback restores the bypass and needs no data change.
- Wide event: `rate_limit.reject_reason` appears on 429s from the deploy on; queries on `outcome=rejected` keep
  working.

### 9. Notation (rule 2)
- `<class>` in `Rate limiting unavailable, failing open: <class>` is `e.javaClass.name` of the caught exception
  (fully qualified, `$` before nested class names), e.g. `io.lettuce.core.RedisConnectionException`.
- `N` in tests is the number of parallel callers released together by one `CountDownLatch`.
- Bucket4j's message `CAS operation failed after <K> retry attempts`: `<K>` is the configured `maxRetries`, i.e. 32.

## Assumptions verified
| Claim | How checked |
|---|---|
| Versions: Bucket4j 8.22.0 (core, lettuce, redis-common), Lettuce 7.5.2, Kotlin 2.3.21, JDK 21 | `services/core/gradle.lockfile`, `gradle/libs.versions.toml`; experiments below used these jars from `~/.gradle/caches`, Temurin 21.0.7, `redis:8.10.3-alpine` on localhost |
| Without a retry cap the CAS loop runs until the deadline; `maxRetries(K)` gives K attempts in total; exhaustion throws `BucketExceptions.maxRetriesExceeded(K)` = exact `BucketExceptions$BucketExecutionException`, cause null, message `CAS operation failed after K retry attempts` | `javap -c` of `AbstractCompareAndSwapBasedProxyManager.execute` (`maxRetries.orElse(Integer.MAX_VALUE)`, loop `i < maxAttempts`) and `BucketExceptions.maxRetriesExceeded`; run: `maxRetries(1)`, 20 parallel calls on one key → 1–2 allowed, 18–19 with that message and `K` = 1 (5 runs) |
| `io.github.bucket4j.TimeoutException` extends `BucketExecutionException`, has no cause; it is thrown when a command gets no answer within the remaining deadline and when the deadline passed before the next command | `javap` of `TimeoutException`, `Timeout$2.call`, `LettuceBasedProxyManager.getFutureValue` (`java.util.concurrent.TimeoutException` → `io.github.bucket4j.TimeoutException`; `RedisException` causes rethrown as they are) |
| No other producer of an exact `BucketExecutionException` without cause is on the limiter's path | Scan of every class in the three Bucket4j jars: `executionException(cause)` only in `ExecutionStrategy$2/$3` (not the default `SAME_TREAD`, builder bytecode), `from(cause)` only in lock-based and select-for-update managers, `SQLProxyConfigurationBuilder` is JDBC |
| The Bucket4j deadline is measured per `execute()` call | `Timeout.of(clientSideConfig)` at the start of each `execute` (bytecode) |
| The bypass needs a bucket with many tokens; capacity 120 and N = 1000 reproduce it on `origin/main` | Java harness mirroring `RedisRateLimiter` at 68a07fa (same Bucket4j/Lettuce calls and options): capacity 120: N = 1000 → 35–41 allowed + 959–965 `TimeoutException` (3/3), N = 500 3/3, N = 300 0/3, N = 200 1/3; capacity 10: no bypass up to N = 1000 (an empty bucket needs no CAS write) |
| `maxRetries(32)` alone does not close the bypass | Harness: N = 1000 → 964–973 failed open, N = 2000 and 5000 likewise (3/3 each) |
| Cap 16 + `maxRetries(32)` closes it and keeps calls fast | Harness: N = 200 / 1000 / 2000 / 5000 → 0 failed open, 16–20 allowed, rest contention, slowest ≤ 12 ms (3 runs each) |
| ≤ 16 parallel calls on one key are all allowed; two limiter instances with 16 each are all allowed | Harness: 16 parallel → 16 allowed, 30/30 runs; two instances (own connection, own cap) × 16 → 960/960 allowed over 30 runs, slowest 43 ms |
| A flood on one key does not change other keys' decisions | Harness: 2000 parallel on one key + 50 other keys → 50/50 others allowed (3/3) |
| Direct `ProxyManager` calls with `maxRetries(32)`, 100 parallel, capacity 120 show the retry error reliably; without `maxRetries` never | Harness: 37–40 exhaustions per run, 0 failed open, slowest ≤ 65 ms (10/10); with 50 parallel only 0–4 (not used); `origin/main` settings, 100 parallel → 0 exhaustions (3/3) |
| Failure classes by phase | Harness: closed port → `RedisConnectionException` (cause `AnnotatedConnectException`) in 1–295 ms; accepting socket that never answers → `RedisConnectionException` (cause `RedisCommandTimeoutException`) in ~300 ms; dropped connection (proxy closed, 100 ms wait) → `io.lettuce.core.RedisException` in 0 ms (3/3); stall on an open connection (proxy stops forwarding) → `io.github.bucket4j.TimeoutException` in 249–253 ms (30/30) |
| With Redis stalled, 16 in-flight calls hold the cap and the 17th returns at once | Harness: 17th → contention in 0 ms; the 16 → `TimeoutException`; another key → `TimeoutException` at 252 ms |
| `getClientSideConfig()` is protected, so tests cannot read `maxRetries` directly (hence the behaviour test) | `javap` of `AbstractProxyManager` |
| The Kotlin in §2–§3 compiles with `-Werror` and behaves as stated: `isRetryExhaustion` true only for `maxRetriesExceeded`, false for `timeoutReached`, `executionException(cause)` and other exceptions; `KeyInFlightLimit` turns away a call over the cap and leaves no entry, also after a throw; the builder chain types as `ProxyManager<String>`; `assertEquals(1, rejected.remaining)` still compiles with `Long?` | Compiled and ran a snippet with `kotlin-compiler-embeddable` 2.3.21 from the Gradle cache (`-Werror -jvm-target 21`) |
| `rate_limit.reject_reason` is not redacted; enums are written as lowercase names | `Redaction.kt` (sensitive words list; `value is Enum<*> -> value.name.lowercase()`) |
| The `tryConsume` body of §3 (smart cast of `probe` in the `when`, `CONTENDED` in the companion) compiles with `-Werror` and maps `maxRetriesExceeded` to `Rejected(remaining=null, retryAfterSeconds=1, reason=CONTENTION)` and `timeoutReached` to `Unavailable` | Same compiler, a copy of the body against the Bucket4j jars, run |
| AC 8's grep prints exactly the `LazyConnection.kt:24` line on the current tree | Ran it on `plan/audit-fixes` (= `origin/main` for these files) |
| The Allowed paths parse as intended | `scripts/agentic/scope.sh allowed` on this spec: exit 0 for every listed file and `docs/backlog/x.md`; exit 1 for `RedisRateLimiterTest.kt`, `RateLimitConfiguration.kt`, `application.yaml` and the spec itself |
| Only `RedisRateLimiterTest` (not in Allowed paths) uses the decision types outside the listed files, and it compiles unchanged with `remaining: Long?` | `grep` for `RateLimitDecision.` / `.remaining` in `services/core/src`; compile check above |
| UNVERIFIED: in the cluster one CAS attempt (GET + EVAL round trip) takes < ~7.8 ms, so 32 attempts end before the 250 ms deadline | Cannot run before tasks 010/011; recorded under Limits and as backlog candidate (2) |
| UNVERIFIED: the Kotlin `RedisRateLimiterContentionTest` fails on `origin/main` | Shown with the Java mirror only; the implementer checks first (AC 1) |

## Limits
| Limit | Value | Wrapped by (must be larger) |
|---|---|---|
| CAS attempts per bucket operation (`MAX_CAS_ATTEMPTS`, Bucket4j `maxRetries`) | 32 attempts | The Bucket4j deadline (250 ms): 32 attempts must end before it, i.e. one attempt < ~7.8 ms. Localhost: 100 callers without the cap end in ≤ 65 ms. Cluster: UNVERIFIED. If attempts are slower, the deadline fires first and the call fails open (bounded by the next row) |
| In-flight operations per bucket key per JVM (`MAX_IN_FLIGHT_PER_KEY`) | 16 | 32 attempts: 16 callers need ≤ 16 attempts; 32 covers the callers of two pods (measured 960/960 allowed). In a slow environment it also bounds one key's fail-open calls to 16 per pod at a time (≤ 64 per second at 250 ms each) |
| Waiting for an in-flight slot | none (a full key returns contention at once) | Keeps one limiter call ≤ one deadline, as today |
| Bucket4j deadline per operation (`tastebuds.rate-limit.timeout`) | 250 ms default, ≤ 5 s (unchanged) | One request: session call + IP call ≤ 2 × `timeout` (F1, unchanged, backlog) — inside client and ingress timeouts; the ingress is not deployed yet and its task must set them above 2 × `timeout`. When the deadline fires, the caller returns and frees its slot; the command it sent stays pending in Lettuce until Lettuce's own command timeout (same 250 ms, counted from sending) completes it, so the two are not nested: the Bucket4j deadline is the one that stops the caller (measured: it fires first, 249–253 ms) |
| `Retry-After` for contention | 1 s | — |
| WARN for store errors | ≤ 1 per 60 s per limiter (unchanged); contention never logs | — |
| Lettuce connect timeout, reconnect backoff | 250 ms, 5 s (unchanged) | — |
| Test bounds | N = 1000; every thread join ≤ 30 s; connect failures < 2 s; stall: ≥ 200 ms and < 2 s; dropped connection: wait 200 ms after `close()`, then < 1 s; outage probe: 100 ms after the 16 callers started, answer < 100 ms | The 16 stalled callers hold their slots for ~250 ms, longer than probe start + probe (≤ 200 ms) |

## Acceptance criteria
1. **Reproduced first.** `RedisRateLimiterContentionTest` uses only symbols that exist on `origin/main` (no
   `RejectReason`, no new constants; the limits appear as the literals 16 and 32). Before any main-source change, the
   implementer runs it on unchanged main code: both "never more than the bucket holds" tests and the compare-and-swap
   test fail. The handoff notes quote the failure lines (decision tally with `Unavailable` > 0; no retry error). The
   reviewer may copy the file into a worktree of `origin/main` and see the same.
2. **Contention never fails open.** N = 1000 parallel `tryConsume` calls on one key give no `Unavailable`, between 1 and
   120 `Allowed`, and otherwise only `Rejected` — for an IP key and for a session key (default capacities 120,
   timeout 250 ms).
3. **Contention is a 429.** In-flight cap reached → `Rejected(null, 1, CONTENTION)` without a Redis call; Bucket4j retry
   exhaustion → the same decision. `RateLimitFilter` answers with the task 001 429 problem body, `Retry-After: 1`, wide
   event `rate_limit.outcome=rejected`, `rate_limit.reject_reason=contention`, `rate_limit.limited_by` = `session` or
   `ip`, and no `rate_limit.remaining`. A contended session bucket is not followed by an IP call. Contention writes no
   log line.
4. **Bucket rejections are unchanged** except the new field `rate_limit.reject_reason=tokens`.
5. **Normal parallelism is unaffected.** 16 parallel calls on one key with tokens left are all `Allowed`; a flood of
   1000 on one key leaves 50 calls on other keys `Allowed(119)`.
6. **Store failures still fail open, and the log names them.** Closed port and an accepting socket that never answers
   → `Unavailable` in < 2 s, the one WARN names `io.lettuce.core.RedisConnectionException`; dropped connection →
   `Unavailable` in < 1 s, WARN names `io.lettuce.core.RedisException`; stall on an open connection → `Unavailable`
   after ≥ 200 ms and < 2 s, WARN names `io.github.bucket4j.TimeoutException`. A `TimeoutException` and a
   `BucketExecutionException` with a cause, thrown by the store, give `Unavailable`.
7. **The cap holds in an outage.** With Redis stalled and 16 calls on a key in flight, the 17th call on that key returns
   contention in < 100 ms; the 16 return `Unavailable`.
8. **No latency added.** No code path waits for a slot; contention returns without a Redis call or before the
   deadline. Check: `grep -rn -E 'Thread\.sleep|tryLock\(|\.await\(|\.acquire\(|\.lock\(\)|synchronized'
   services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/` prints exactly one line, the existing
   non-waiting `if (!lock.tryLock()) return null` in `LazyConnection.kt` (same output as on `origin/main` today).
9. **No leftovers.** `KeyInFlightLimit` keeps no entry for a key without a running call, also after a call threw, and
   never runs more than its limit per key at once.
10. **Unchanged:** bucket key names and TTL, `tastebuds.rate-limit.*` properties and validation, `LazyConnection`,
    Lettuce options, the filter position and bucket order, outcome values, the constructors of `RedisRateLimiter` and
    the signatures of `RateLimitRedis`. Existing tests pass; the only edits to existing tests are those listed under
    Required tests.
11. **Docs** exactly as §5. `WIDE_EVENT_SCHEMA.md` documents `rate_limit.reject_reason`.
12. **Privacy.** No bucket key, client address or session id appears in a log line, a wide event, a response or a test
    assertion message (messages show decision tallies only).

## Required tests
- `RedisRateLimiterContentionTest` (new; shared Testcontainers Redis via `RedisTestcontainersConfiguration.redis`, no
  Spring context; limiter built as in `RedisRateLimiterTest.limiter()` with `RateLimitProperties()` defaults; before
  each burst one sequential call on another unique key must be `Allowed`, so the connection and script are warm; every
  burst starts N virtual threads that wait on one `CountDownLatch`, and joins each with a 30 s bound):
  - `a thousand parallel requests never take more than the IP bucket holds` (key `ClientKey("test:" + UUID, IP4)`):
    no `Unavailable` (message: the tally by decision class), `Allowed` count in 1..120, every decision `Allowed` or
    `Rejected`.
  - `a thousand parallel requests never take more than the session bucket holds` (key `ClientKey("as:" + UUID,
    SESSION)`): the same assertions.
  - `sixteen parallel requests on one key are all allowed`: 16 × `Allowed`.
  - `a flood on one key leaves other keys alone`: 1000 calls on one key and one call on each of 50 other unique keys,
    all released by the same latch; the 50 are `Allowed(119)`.
  - `compare-and-swap gives up after 32 lost attempts with Bucket4j's retry error`: `RateLimitRedis.proxyManager(client,
    250 ms)` called directly (no limiter, no cap) from 100 threads on one key with a 120-token greedy bucket; at least
    one thrown exception has exact class `BucketExceptions.BucketExecutionException`, no cause, and message
    `CAS operation failed after 32 retry attempts`.
- `RedisRateLimiterFailOpenTest` (extend; each test builds its own limiter, so its first failure logs; helper
  `warnedClasses(output)` = every match of `Rate limiting unavailable, failing open: ([\w.$]+)` in `output.all`; proxy
  tests point the limiter at `127.0.0.1:proxy.port` with the shared Redis as upstream, make one `Allowed` call before
  stalling or dropping (on another key in the 17th-call test, so the connection exists before the 16 callers start),
  and shut their `RedisClient` down in `finally`. These tests use an IP key only: the `catch` does not depend on the
  key type, while contention, which does (prefix and configuration), is tested for both key types above):
  - `an unreachable Redis means unavailable, without waiting` (existing) + assert
    `warnedClasses(output) == listOf("io.lettuce.core.RedisConnectionException")`.
  - Rename `a Redis that never answers means unavailable within the timeout` to
    `a Redis that never answers the connection handshake means unavailable within the timeout` + the same WARN
    assertion.
  - `a Redis that stalls on an open connection fails open within the timeout`: after `proxy.stall()`, `Unavailable`,
    elapsed ≥ 200 ms and < 2 s, `warnedClasses == listOf("io.github.bucket4j.TimeoutException")`.
  - `a dropped connection fails open at once`: `proxy.close()`, wait 200 ms, then `Unavailable` in < 1 s,
    `warnedClasses == listOf("io.lettuce.core.RedisException")`.
  - `while Redis stalls, a seventeenth parallel call on one key is rejected at once`: stall; start 16 calls on one key;
    after 100 ms one more call on that key returns `Rejected(null, 1, RejectReason.CONTENTION)` in < 100 ms; the 16
    return `Unavailable`.
  - `retry exhaustion is contention, never a fail open, and is not logged`: supplier throws
    `BucketExceptions.maxRetriesExceeded(32)` → `Rejected(null, 1, RejectReason.CONTENTION)` and no "Rate limiting
    unavailable" line; the next call on the same limiter with a supplier failure (`IllegalStateException`) still logs
    one WARN (contention did not use up the throttle).
  - `Bucket4j timeouts and wrapped store errors fail open`: `BucketExceptions.timeoutReached(300_000_000, 250_000_000)`
    → `Unavailable`, WARN `io.github.bucket4j.TimeoutException`; a new limiter with
    `BucketExceptions.executionException(IllegalStateException())` → `Unavailable`, WARN
    `io.github.bucket4j.BucketExceptions$BucketExecutionException`.
  - `no connection means unavailable` and the WARN throttle test stay unchanged.
- `KeyInFlightLimitTest` (new, unit):
  - `admits calls up to the limit for one key and turns the next one away at once` (limit 2; two calls blocked on a
    latch inside `block`; a third returns null without running `block`; after release a call is admitted again).
  - `each key has its own limit`.
  - `a slot is freed when the call returns or throws` (`trackedKeys() == 0` afterwards; the exception reaches the
    caller).
  - `never more than the limit run at once per key, and nothing stays behind` (1000 parallel short calls over 10 keys,
    limit 4; the highest concurrent count observed inside `block` per key is ≤ 4; `trackedKeys() == 0` at the end).
  - `a limit below one is refused` (`IllegalArgumentException`, message `maxPerKey must be at least 1`).
- `RateLimitFilterTest` (extend): existing `Rejected(...)` constructions get `RejectReason.TOKENS`;
  `rejected requests get a 429 problem with Retry-After and stop` and
  `a session over its limit is rejected without charging the IP bucket` also assert `reject_reason` = `tokens`; new:
  - `contention on the IP bucket gets a 429 with Retry-After 1 and no remaining` (status, `Retry-After` `1`, problem
    body as in the existing 429 test, `outcome` `rejected`, `reject_reason` `contention`, `limited_by` `ip`, no
    `remaining`, chain not called).
  - `contention on the session bucket gets a 429 without asking the IP bucket` (`limited_by` `session`, limiter keys =
    `[SESSION]`).
- `RateLimitIntegrationTest` (extend): in `rule costs apply and the request over the limit gets a 429 problem`, the
  rejected event has `rate_limit.reject_reason` = `tokens`.

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- Kover line coverage of `dev.danya.tastebuds.core.platform.ratelimit` ≥ **80%** (global floor: 70%).
- Wide event fields added for new behaviour are documented in docs/observability/WIDE_EVENT_SCHEMA.md.
- `scripts/agentic/scope.sh diff docs/tasks/012-rate-limit-contention-fail-closed.md` reports no file outside the
  Allowed paths.
- No new dependency.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
