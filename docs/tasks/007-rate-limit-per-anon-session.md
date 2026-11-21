# 007 — Rate-limit bucket per anonymous session, in addition to the IP bucket

- **Status:** DONE
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-20
- **Depends on:** 006

## Decisions (answered 2026-11-20)
1. **Add, not replace.** A request with a valid session token draws from its session bucket first, then from its IP
   bucket; both must allow. The IP bucket stays the abuse ceiling, so new sessions do not multiply what one IP may
   send; the session bucket follows one client across IP changes and keeps one session's retries from draining the IP
   bucket it shares with NAT neighbours. Known limit: clients behind one NAT still share the IP bucket.
2. No per-session budgets in Phase 1 (task 006, decision 3).

## Goal
Complete docs/PLAN.md §8.2 "token buckets per IP, per anon session" for Phase 1 (exit criterion: "anon session issued
and rate-limited"). Task 001 keys buckets by client IP behind the `ClientKeyResolver` seam; task 006 issues signed
anonymous session tokens that can be verified without I/O. This task adds a second resolver that keys by session and
makes the filter check every applicable bucket, so a request with a valid session token draws from its session bucket
and its IP bucket. The limiter stays in its one position (after `CorsFilter`, before authentication): it verifies the
token signature itself, so no second position after authentication is needed.

## Non-goals
- Replacing the IP key (decision 1), per-user keys (Phase 4), per-session budgets (decision 2).
- Different costs per key type: a request costs the same in both buckets.
- Any change to the token format, `AnonSessionTokens`, `BearerCredentials` or the authentication filter (task 006).
- Caching the verification result between the limiter and the authentication filter (two HMACs cost about 2 µs).
- `RateLimit-*` headers, Redis topology, the IP key rules, `LazyConnection`, fail-open behaviour (all unchanged).
- Helm, k8s, compose or any `deploy/**` change.

## Allowed paths
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/**`
- `services/core/src/main/resources/application.yaml`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/**`
- `docs/observability/WIDE_EVENT_SCHEMA.md`
- `SECURITY.md`

## Design notes

### 1. Session keys (`ClientKeys.kt`)
```kotlin
internal enum class ClientKeyType { IP4, IP6, INVALID, SESSION }

/** Chooses one bucket a request draws from; null when this resolver has no key for the request. */
internal fun interface ClientKeyResolver {
    fun resolve(request: HttpServletRequest): ClientKey?
}
```
- `IpClientKeyResolver.resolve` keeps the non-null return type `ClientKey` (a valid override); `keyFor` is unchanged.
- New, in the same file:
  ```kotlin
  /** Keys by the anonymous session of a valid bearer token (signature and expiry checked, no I/O); null otherwise. */
  internal class AnonSessionClientKeyResolver(private val tokens: AnonSessionTokens) : ClientKeyResolver {
      override fun resolve(request: HttpServletRequest): ClientKey?
  }
  ```
  `BearerCredentials.from(request)` must be `BearerCredential.Present`, and `tokens.verify(token)` must be
  `AnonSessionVerification.Valid`; then `ClientKey("as:${principal.sessionId}", ClientKeyType.SESSION)`. Anything else
  (absent, other scheme, malformed, unknown key, bad signature, expired) → `null`: such a request draws from its IP
  bucket only, and task 006's filter then answers 401 if it carried a bad token.
- Session keys match `SESSION_KEY_SHAPE = ^as:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` (39
  characters). Like IP keys, they are never logged and never put into a wide event or a response.

### 2. Configuration (`RateLimitProperties.kt`)
Add, after `refillPeriod`:
```kotlin
    /** Bucket of each anonymous session, checked before the client's IP bucket (task 007). */
    val session: SessionLimit = SessionLimit(),
```
and the nested type:
```kotlin
    data class SessionLimit(
        val capacity: Long = 120,
        val refillTokens: Long = 120,
        val refillPeriod: Duration = Duration.ofMinutes(1),
    )
```
(Use the existing `DEFAULT_TOKENS` constant for the two `120` values.) Validation order in `init`: `capacity`,
`refill-tokens`, `refill-period`, then the three session rows, then `default-cost`, `timeout`, `reconnect-backoff`,
`rules`, so each invalid value reports its own name:

| Property | Valid | Message |
|---|---|---|
| `session.capacity` | 1..1_000_000 | `tastebuds.rate-limit.session.capacity must be between 1 and 1000000` |
| `session.refill-tokens` | 1..1_000_000 | `tastebuds.rate-limit.session.refill-tokens must be between 1 and 1000000` |
| `session.refill-period` | ≥ 1 s | `tastebuds.rate-limit.session.refill-period must be at least 1s` |
| `default-cost` | 1..min(`capacity`, `session.capacity`) | `tastebuds.rate-limit.default-cost must be between 1 and the smaller of capacity and session.capacity` |
| `rules[n].cost` | 1..min(`capacity`, `session.capacity`) | `tastebuds.rate-limit.rules[n].cost must be between 1 and the smaller of capacity and session.capacity` |

`application.yaml`, under `tastebuds.rate-limit`, directly after `reconnect-backoff:` and before `rules:`:
```yaml
    session:                 # bucket per anonymous session, checked before the IP bucket; both must allow
      capacity: 120
      refill-tokens: 120
      refill-period: 1m
```

### 3. Bucket store (`RedisRateLimiter.kt`)
- Add `internal fun sessionBucketKeyPrefix(properties: RateLimitProperties): String` =
  `"rl:${session.capacity}-${session.refillTokens}-${session.refillPeriod.toMillis()}:"`. `bucketKeyPrefix` is
  unchanged, so existing IP buckets keep their keys. Full session keys look like `rl:120-120-60000:as:<uuid>`; they
  never collide with IP keys (`ip4:`, `ip6:`, `ip:` namespaces) even when both configurations are equal.
- `RedisRateLimiter` builds two `BucketConfiguration`s (IP: top-level values; session: `properties.session`) with
  their prefixes once, and picks by `key.type == ClientKeyType.SESSION`. Everything else (fail open, WARN throttle,
  expiration strategy, timeout) is unchanged and shared.

### 4. The filter (`RateLimitFilter.kt`)
Constructor: `keys: List<ClientKeyResolver>` instead of one resolver, in check order (session first, IP last).
`doFilterInternal`:
1. `val resolved = keys.mapNotNull { it.resolve(request) }`; `cost = costs.resolve(request)`.
2. Wide event as today: `rate_limit.rule`, `rate_limit.cost`, and `rate_limit.key_type` = the type of the first
   resolved key whose type is not `SESSION` (the IP key; unchanged meaning).
3. For each key in order, `limiter.tryConsume(key, cost.tokens)`:
   - `Allowed` → remember the smallest `remaining` so far; next key.
   - `Rejected` → `rate_limit.outcome=rejected`, `rate_limit.remaining` = this decision's remaining,
     `rate_limit.limited_by` = `session` for a `SESSION` key, else `ip`; `Retry-After` from this decision; the same
     429 problem as task 001; stop (later buckets are not charged, the chain is not called).
   - `Unavailable` → `rate_limit.outcome=unavailable`; call the chain; stop (no further Redis calls: if the store failed
     once, a second call would only add another timeout).
4. All allowed → `rate_limit.outcome=allowed`, `rate_limit.remaining` = the smallest remaining; call the chain.

Why session first: a session that is over its own limit is rejected before it touches the IP bucket, so its retries
do not drain the bucket it shares with other clients behind the same NAT.

### 5. Wiring (`RateLimitConfiguration.kt`)
`rateLimitSecurityCustomizer` gets one more parameter, `sessions: ObjectProvider<AnonSessionTokens>`, and builds
`RateLimitFilter(listOfNotNull(sessions.ifAvailable?.let { AnonSessionClientKeyResolver(it) }, keys), RequestCostResolver(properties), limiter)`.
The `clientKeyResolver` bean (IP) stays. `ObjectProvider`, because `RateLimitConfigurationTest` loads the
configuration without task 006's beans; there the limiter keeps IP keys only. In the application both configurations
exist in the `api` role, and `AnonSessionRateLimitIntegrationTest` proves the session resolver is wired.

### 6. Wide event fields
| Field | Type | Description |
|---|---|---|
| `rate_limit.remaining` | long | changed description: Tokens left after this request in the bucket that decided: the smaller of the session and IP buckets when allowed, the rejecting bucket when rejected; absent when `unavailable` |
| `rate_limit.limited_by` | string | Only when `rejected`: `session` (the anonymous session's bucket) or `ip` (the client address bucket) |

`rate_limit.key_type` keeps its meaning (the IP key type).

### 7. Docs
- `docs/observability/WIDE_EVENT_SCHEMA.md`, "Rate limiting" table: replace the `rate_limit.remaining` description
  with the §6 text (without "changed description: "), and add the `rate_limit.limited_by` row after
  `rate_limit.key_type`. Under the table, change "Never the client address or the bucket key (personal data)." to
  "Never the client address, the session id or the bucket key (personal data)." Change nothing else.
- `SECURITY.md`, the rate-limiting bullet: replace "keyed by client IP (IPv6 by /64) and checked before
  authentication" with "keyed by client IP (IPv6 by /64) and, for requests with a valid anonymous session token, also
  by session (both buckets must allow), checked before authentication". Change nothing else.

### Expected size
About 120–160 changed lines of main code and YAML; tests mirror it.

## Acceptance criteria
1. **Both buckets.** A `/v1/**` request with a valid anonymous session token draws `cost` tokens from its session
   bucket and then from its IP bucket; it reaches the handler only if both allow. Its wide event has
   `rate_limit.outcome=allowed` and `rate_limit.remaining` = the smaller remaining.
2. **Session limit.** When the session bucket rejects, the response is the task 001 429 problem with `Retry-After`
   from the session bucket, the IP bucket is not charged, and the wide event has `rate_limit.limited_by=session`.
   The session bucket follows the session across IP addresses.
3. **IP ceiling.** When the session bucket allows and the IP bucket rejects, the response is 429 with
   `rate_limit.limited_by=ip`. Requests from one IP with many different sessions together never exceed the IP bucket.
4. **No session.** Requests without a token, with another scheme, or with a malformed, forged, unknown-key or expired
   token use the IP bucket only, as before this task; `rate_limit.key_type` keeps its meaning.
5. **Fail open.** When the first store call returns `Unavailable`, no further bucket is asked, the request continues,
   and the outcome is `unavailable` without `remaining`.
6. **Config and keys.** `tastebuds.rate-limit.session.*` with the defaults and validation of §2 (including costs above
   `session.capacity`). Session bucket keys have the form `rl:<s.capacity>-<s.refillTokens>-<s.refillPeriodMillis>:as:<uuid>`
   and a TTL; IP bucket keys are unchanged.
7. **Position.** The limiter is still one filter right after `CorsFilter`, and it runs before task 006's
   authentication filter.
8. **Wiring.** Without an `AnonSessionTokens` bean the limiter starts and keys by IP only (`RateLimitConfigurationTest`
   stays green unchanged).
9. **Privacy.** Session ids, tokens and bucket keys never appear in a response, a wide event or a log line.
10. **Docs.** WIDE_EVENT_SCHEMA.md and SECURITY.md as in §7.

## Required tests
All under `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/`. Secrets are generated at
runtime, never literals.

- `AnonSessionClientKeyResolverTest` (unit; tokens and keys from task 006's `AnonSessionTestKeys`, used as is; AC 4, 9):
  `a valid session token keys by the session` (`ClientKey("as:<id>", SESSION)`, matches `SESSION_KEY_SHAPE`);
  `requests without a valid session token have no session key` (negative: no header, `Basic dXNlcjpwYXNz`, `Bearer`,
  `Bearer garbage`, a token from another key with the same id, a token with an unknown kid, an expired token, two
  `Authorization` headers → `null`).
- `RateLimitPropertiesTest` (extend): `defaults are valid` also asserts `session` = 120/120/1 min and
  `sessionBucketKeyPrefix` = `rl:120-120-60000:`; `invalid values are rejected` adds the three session rows (both
  bounds where there are two) and `default-cost` / `rules[0].cost` above `session.capacity` with a larger `capacity`;
  `the session bucket key prefix changes with the session configuration`.
- `RedisRateLimiterTest` (extend, real Redis): `session keys use the session bucket configuration` (IP capacity 3,
  session capacity 2, both refill per 1 h, cost 1: session key → allowed, allowed, rejected; an IP key → three times
  allowed); `session buckets live under the session prefix and expire when idle` (`PTTL` of
  `rl:2-2-3600000:as:<uuid>` > 0).
- `RateLimitFilterTest` (extend; a fake limiter that records the keys it was called with and answers per key type):
  existing tests construct the filter with `listOf(IpClientKeyResolver())`; new:
  - `the session bucket is checked before the IP bucket` (call order: `SESSION`, then `IP4`)
  - `a session over its limit is rejected without charging the IP bucket` (session `Rejected(0, 7)` → 429,
    `Retry-After: 7`, `limited_by=session`, IP key never passed to the limiter)
  - `the IP bucket still limits requests with a session` (session `Allowed`, IP `Rejected` → 429, `limited_by=ip`)
  - `remaining is the smaller of both buckets` (session `Allowed(5)`, IP `Allowed(9)` → `remaining=5`)
  - `an unavailable store is asked once and the request continues` (session `Unavailable` → one limiter call, chain
    called, outcome `unavailable`, no `remaining`)
- `AnonSessionRateLimitIntegrationTest` (new Spring context: `@SpringBootTest(properties = [...])`,
  `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`,
  `@Import(TestcontainersConfiguration::class, RedisTestcontainersConfiguration::class)`,
  `@ExtendWith(OutputCaptureExtension::class)`. Properties: `tastebuds.rate-limit.enabled=true`, `capacity=4`,
  `refill-tokens=4`, `refill-period=1h`, `session.capacity=2`, `session.refill-tokens=2`, `session.refill-period=1h`,
  `rules[0].method=GET`, `rules[0].pattern=/v1/meta`, `rules[0].cost=1` (the list replaces the YAML rules, whose
  session-creation cost 10 would exceed capacity 4). Tokens come from `@Autowired AnonSessionIssuer`; every test uses
  its own addresses from `203.0.113.0/24` / `198.51.100.0/24` and its own sessions; target `GET /v1/meta`):
  - `a session draws from its own bucket and from the IP bucket` (one session, one IP: 200, 200, then 429 with
    `limited_by=session`; the same IP without a token → 200 with `remaining=1`) (AC 1, 2)
  - `a session keeps its bucket when its address changes` (two requests from IP A, then IP B → 429,
    `limited_by=session`) (AC 2)
  - `new sessions do not multiply the IP bucket` (negative, abuse: five new sessions from one IP, one request each:
    four 200, the fifth 429 with `limited_by=ip`) (AC 3)
  - `a session over its limit does not drain its neighbours' IP bucket` (session X from IP C: 200, 200, then three
    429s; session Y from IP C → 200, 200) (AC 2)
  - `invalid tokens are limited by IP only` (negative: `Bearer tba1.x.y.z` from IP D: four 401s, then 429 with
    `limited_by=ip`) (AC 4)
  - `the limiter runs before anonymous-session authentication` (from `FilterChainProxy.filterChains`: the index of the
    single `RateLimitFilter` is below the index of the single `AnonSessionAuthenticationFilter`) (AC 7)
  - `session ids and tokens never appear in wide events` (no captured `wide_event` line contains any token or session
    id used in this class) (AC 9)

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- Kover line coverage of `dev.danya.tastebuds.core.platform.ratelimit` ≥ **80%** (global floor: 70%).
- Wide event changes of §6 are documented in docs/observability/WIDE_EVENT_SCHEMA.md.
- No new dependency; no lockfile changes.
- `scripts/agentic/scope.sh diff docs/tasks/007-rate-limit-per-anon-session.md main` reports no violation.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->

### Round 1: CHANGES_REQUESTED
1. **The privacy test checks only its own session, not "any token or session id used in this class".**
   `AnonSessionRateLimitIntegrationTest.kt:52` and `:133-143`. `issued` is an instance field and JUnit creates a new
   instance per test, so the privacy test only checks the one session it issues itself. It also never sends a valid
   session that the IP bucket rejects (`limited_by=ip`). Fix with either (a) the privacy test drives every limiter
   branch itself (allowed, rejected by session, rejected by IP with valid sessions, invalid token) and checks all tokens
   and session ids it issued; or (b) move the collection to the `companion object` and assert in an `@AfterEach` that
   takes `CapturedOutput`.

Non-blocking: add a second "smallest remaining" case with session 9 and IP 5 (`RateLimitFilterTest.kt:137-143`);
update the `RateLimitFilter` KDoc ("from the client's bucket"); rename local `session` to `isSession` in
`RedisRateLimiter.kt:59`.

Process note: the implementer wrote the main-code edits with a Python heredoc via Bash, against
`.claude/agents/implementer.md`. The reviewer read every affected file in full and found no collateral damage. The hook
gap is already in `docs/backlog/2026-11-18-scope-guard-ignores-shell-writes.md`.

Backlog: `docs/backlog/2026-11-21-rate-limit-no-refund-on-later-reject.md`,
`docs/backlog/2026-11-21-client-key-tostring-leaks-identity.md`.


### Round 2: APPROVE
Finding 1 fixed with option (a): the privacy test drives every limiter branch (allowed, rejected by session, rejected by
IP with valid sessions, invalid token) and checks every token and session id it issued. All three optional suggestions
applied. `./gradlew :services:core:test --rerun check` passes; Kover line coverage of the ratelimit package is 216/224
(96.4%). Scope check clean.

Non-blocking notes: the privacy test does not assert the statuses of its first three requests (other tests cover that
behaviour); `issued` could be a plain list.
