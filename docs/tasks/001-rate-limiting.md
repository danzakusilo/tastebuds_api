# 001 — Cost-weighted rate limiting per client IP (Redis + Bucket4j)

- **Status:** DONE
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-20 (revised in place for the current `main`; first planned 2026-11-15,
  before tasks 002–005 and the 2026-11-20 revision of docs/PLAN.md)
- **Depends on:** 003, 004, 005 (all DONE)
- **Resolves backlog:** `docs/backlog/2026-11-18-rate-limit-client-key-validation.md` (Design notes §3). Delete that
  file at handoff.

## Goal
Protect `/v1/**` against abuse and cost-DoS with distributed, cost-weighted token buckets (docs/PLAN.md §8.2, D13).
Each request costs tokens according to a configured rule (deep matching will cost far more than search, later). Buckets
are keyed per client: by IP until anonymous sessions exist (separate Phase 1 task). The IP is `request.remoteAddr`,
which Tomcat resolves from trusted proxies only (task 004, docs/security/FORWARDED_HEADERS.md); this task validates and
normalises it. Bucket state lives in Redis, so limits hold across `api` replicas. The limiter runs before
authentication, so unauthenticated traffic is limited too, and after CORS, so the web client (tastebuds_spec F01 shows
a countdown from `Retry-After`) can read the 429. Over the limit: `429` problem+json with `Retry-After`. A Redis
outage never takes the API down (fail open). The wide event of every limited request records the outcome.

### Changes from the 2026-11-15 version (for the reviewer)
- Client IP from `request.remoteAddr` only (task 004). `trusted-proxy-count` and all `X-Forwarded-For` parsing are gone.
- The address is validated and normalised (backlog item above): IPv6 by /64, IPv4-mapped as IPv4, garbage to one shared bucket.
- A filter inside the Spring Security chain, right after `CorsFilter`, replaces the `HandlerInterceptor` + advice.
  Reason in §6. Costs therefore match configured path patterns, not the MVC route.
- Redis access fits Boot 4 and Bucket4j 8.22.0 as they resolve today (§1, §5): lazy, fail-fast, time-bounded connection.
- Dependency lockfile, a JVM-singleton Redis Testcontainer, rate limiting off in the `test` profile, Redis in `bootTestRun`.
- `RateLimit-Limit` / `RateLimit-Remaining` response headers are dropped (Non-goals).

## Non-goals
- Anonymous sessions, session or user keys. Only the `ClientKeyResolver` seam exists (§3, §6 "Later").
- `RateLimit-*` response headers: the IETF draft still changes shape, no client reads them, and browsers could only
  read them after a CORS `exposedHeaders` change. Revisit when the draft is an RFC.
- Compose, Helm, k8s or any `deploy/**` change. Redis in the cluster comes with the Helm chart task (Phase 1, D16).
- Redis Sentinel, Cluster, master/replica or TLS for the limiter's connection: startup fails fast instead (§5).
- Ingress or Cloudflare limits, login throttling (identity service), CAPTCHA, per-viewer view quotas (§8.2), global
  limits, Micrometer metrics for the limiter (wide events cover it).
- Costs for endpoints that do not exist yet. Only the rule mechanism and the `GET /v1/meta` rule.
- Any change to `ProblemDetailsAdvice`, `ProblemResponses`, `RequestBodyLimitFilter`, the CORS settings (exposed
  headers already include `Retry-After`), the forwarded-header config, the wide-event library, or health/probe settings.
- `TestcontainersConfiguration.kt` and the per-context Postgres container
  (`docs/backlog/2026-11-18-shared-testcontainers-postgres.md`). Redis starts as a JVM singleton (§9); Postgres stays as is.
- Hardened headers on the 413 (`docs/backlog/2026-11-18-pre-security-responses-lack-hardened-headers.md`).
- `CLAUDE.md` (see "Note for the user").

## Allowed paths
- `gradle/libs.versions.toml`
- `services/core/build.gradle.kts`
- `services/core/gradle.lockfile`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/**`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/security/SecurityConfiguration.kt`
- `services/core/src/main/resources/application.yaml`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/**`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/RedisTestcontainersConfiguration.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/TestTastebudsCoreApplication.kt`
- `services/core/src/test/resources/application-test.yaml`
- `docs/observability/WIDE_EVENT_SCHEMA.md`
- `SECURITY.md`
- `README.md`

## Design notes

### 1. Dependencies (verified 2026-11-20)
`gradle/libs.versions.toml`: under `[versions]` add `bucket4j = "8.22.0"`; under `[libraries]` add a `# rate limiting`
group:
```toml
bucket4j-core = { module = "com.bucket4j:bucket4j_jdk17-core", version.ref = "bucket4j" }
bucket4j-lettuce = { module = "com.bucket4j:bucket4j_jdk17-lettuce", version.ref = "bucket4j" }
```
`services/core/build.gradle.kts`, in `dependencies`, after `spring-boot-starter-flyway`:
```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-redis")
implementation(libs.findLibrary("bucket4j-core").get())
implementation(libs.findLibrary("bucket4j-lettuce").get())
```
Facts the design relies on (checked against Maven Central and the Boot 4.1.2 BOM; do not re-decide):
- Bucket4j 8.22.0 is the latest release of both artifacts. `bucket4j_jdk17-lettuce` brings
  `bucket4j_jdk17-redis-common` and declares Lettuce as `provided`, so Lettuce comes from the starter (BOM:
  `io.lettuce:lettuce-core:7.5.2.RELEASE`). The Bucket4j calls (`eval`, `get`, `del` on async commands) are binary
  compatible with Lettuce 7.5.2.
- Use `io.github.bucket4j.redis.lettuce.Bucket4jLettuce.casBasedBuilder(...)`. `LettuceBasedProxyManager.builderFor(...)`
  is deprecated, and warnings are errors.
- Boot 4.1.2 keeps `spring-boot-starter-data-redis` and the `spring.data.redis.*` properties. The connection details
  type is `org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails` (Boot 3 called it
  `RedisConnectionDetails`). The same module defines the shared Lettuce `ClientResources` bean and
  `DataRedisAutoConfiguration`.
- No new test dependency. Testcontainers 2.0.5 has no Redis module; `GenericContainer` from
  `org.testcontainers:testcontainers` (already on the test classpath) is enough. Do **not** add
  `com.redis:testcontainers-redis`.

Why the starter: Boot-managed Lettuce, one config namespace (`spring.data.redis.*`) with connection details, the shared
`ClientResources`, a Redis health contributor, and the same base for anonymous sessions and caches later (D13).

**Lockfile.** After the build changes, run exactly:
```bash
./gradlew resolveAndLockAll --write-locks --no-configuration-cache
git status --porcelain -- '*.lockfile'
```
Only `services/core/gradle.lockfile` may change. If any other lockfile changes, stop and report `SCOPE_BLOCKED` with
its path. Do not run the `-p build-logic` variant (build-logic does not change).

### 2. Configuration
`platform/ratelimit/RateLimitProperties.kt` (public; `@ConfigurationPropertiesScan` already scans it):
```kotlin
@ConfigurationProperties("tastebuds.rate-limit")
data class RateLimitProperties(
    /** Master switch. Off: no limiter beans and no Redis connection for rate limiting. */
    val enabled: Boolean = true,
    /** Bucket size in tokens: the largest burst one client can send. */
    val capacity: Long = 120,
    /** Tokens added back per [refillPeriod], greedily (spread evenly over the period). */
    val refillTokens: Long = 120,
    val refillPeriod: Duration = Duration.ofMinutes(1),
    /** Tokens a `/v1` request costs when no rule matches. */
    val defaultCost: Long = 1,
    /** Redis connect and command timeout, and the deadline of one bucket operation. Slower means fail open. */
    val timeout: Duration = Duration.ofMillis(250),
    /** Minimum time between two connect attempts while Redis is unreachable. Requests fail open meanwhile. */
    val reconnectBackoff: Duration = Duration.ofSeconds(5),
    /** Cost rules; the first match wins. */
    val rules: List<Rule> = emptyList(),
) {
    data class Rule(
        /** Upper-case HTTP method; null matches every method. */
        val method: String? = null,
        /** Spring `PathPattern` under `/v1/`, matched against the decoded request path, e.g. `/v1/media/{id}`. */
        val pattern: String,
        val cost: Long,
    )
}
```
Validation in an `init` block with `require(...)`. Each message starts with the property name, e.g.
`"tastebuds.rate-limit.default-cost must be between 1 and capacity"`:

| Property | Valid |
|---|---|
| `capacity` | 1..1_000_000 |
| `refill-tokens` | 1..1_000_000 |
| `refill-period` | ≥ 1 s |
| `default-cost` | 1..`capacity` |
| `timeout` | > 0 and ≤ 5 s |
| `reconnect-backoff` | > 0 |
| `rules[n].cost` | 1..`capacity` (a cost above capacity could never be paid) |
| `rules[n].pattern` | starts with `/v1/` |
| `rules[n].method` | null or one of `GET HEAD POST PUT PATCH DELETE OPTIONS` |

A `List` of rules, not a map keyed by path: Boot's binder strips `/` from map keys unless they are bracketed.

`application.yaml`:
- Under `spring:`, after the `flyway:` block:
  ```yaml
    data:
      redis:
        # Rate-limit buckets (platform.ratelimit). The Helm chart sets REDIS_HOST; credentials, if any, via
        # SPRING_DATA_REDIS_USERNAME / SPRING_DATA_REDIS_PASSWORD. Local dev: Testcontainers (bootTestRun).
        host: ${REDIS_HOST:localhost}
        port: ${REDIS_PORT:6379}
  ```
- Under `tastebuds:`, after `web:`:
  ```yaml
    rate-limit:
      # Per-client token buckets in Redis, docs/PLAN.md §8.2. Fails open while Redis is unavailable.
      enabled: true
      capacity: 120          # burst size in tokens
      refill-tokens: 120     # tokens added back per refill period (greedy)
      refill-period: 1m
      default-cost: 1        # tokens per /v1 request without a matching rule
      timeout: 250ms         # Redis connect/command timeout; slower means fail open
      reconnect-backoff: 5s  # pause between connect attempts while Redis is down
      rules:                 # first match wins; method optional (absent = any method)
        - method: GET
          pattern: /v1/meta
          cost: 1
  ```

`application-test.yaml`, appended:
```yaml
tastebuds:
  rate-limit:
    # Off unless a test turns it on and imports RedisTestcontainersConfiguration: other contexts have no Redis.
    enabled: false
```

### 3. Client keys (resolves the backlog item)
`platform/ratelimit/ClientKeys.kt`, all `internal`:
```kotlin
enum class ClientKeyType { IP4, IP6, INVALID }

/** [value] is the bucket identity. Never log it or put it in a wide event or response; [type] is safe to record. */
data class ClientKey(val value: String, val type: ClientKeyType)

/** Chooses whose bucket a request draws from. Phase 1 sessions and Phase 4 users add implementations (docs/PLAN.md §8.2). */
fun interface ClientKeyResolver {
    fun resolve(request: HttpServletRequest): ClientKey
}

/** Keys by `request.remoteAddr` (resolved from trusted proxies only, docs/security/FORWARDED_HEADERS.md). */
class IpClientKeyResolver : ClientKeyResolver {
    override fun resolve(request: HttpServletRequest): ClientKey = keyFor(request.remoteAddr)

    companion object {
        fun keyFor(address: String?): ClientKey
    }
}
```
Never read `X-Forwarded-For` or any other header. A trusted peer can still set `remoteAddr` to any string, so
`keyFor` applies these rules, in this order. All regex checks are whole-input matches (`Regex.matches`); with `find` or
`containsMatchIn`, `$` would also match before a trailing line break.
1. `null`, empty, or longer than 64 characters → invalid.
2. Matches the strict ASCII dotted quad
   `^((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$` (no leading zeros,
   which are ambiguous) → `ClientKey("ip4:$address", IP4)`.
3. Drop a zone id: keep the part before the first `%`.
4. The rest must match `^[0-9A-Fa-f:][0-9A-Fa-f:.]*$` and contain `:`, otherwise invalid. This gate is what keeps
   step 5 free of DNS: for such a string the JDK takes its literal-parsing path, and a string with `:` either parses
   as an IPv6 literal or throws; it never reaches a host name lookup. (A first character such as `.` would fall
   through to DNS, hence the first-character class.)
5. `InetAddress.getByName(rest)`; `UnknownHostException` or `IllegalArgumentException` → invalid.
   - Result is an `Inet4Address` (the JDK returns one for IPv4-mapped input such as `::ffff:203.0.113.7`) →
     `ClientKey("ip4:${hostAddress}", IP4)`.
   - Result is an `Inet6Address` → the /64 prefix: the first four 16-bit groups of `address` as lowercase hex without
     leading zeros, joined by `:`, then `::/64`. Example: `2001:db8:85a3::8a2e:370:7334` →
     `ClientKey("ip6:2001:db8:85a3:0::/64", IP6)`. One subscriber usually holds at least a /64 and can rotate
     addresses inside it freely, so per-address keys would be an evasion.
6. Invalid → `ClientKey("ip:invalid", INVALID)`: every unparsable address shares this one bucket.

Every key matches `KEY_SHAPE = ^(ip4:[0-9]{1,3}(\.[0-9]{1,3}){3}|ip6:[0-9a-f]{1,4}(:[0-9a-f]{1,4}){3}::/64|ip:invalid)$`
and has at most 28 characters, so the Redis key space stays bounded per address. Its total size is bounded by the
trust boundary of task 004 plus key TTLs (§5). Known limit: clients behind one NAT share a bucket until session keys exist.

### 4. Request cost
`platform/ratelimit/RequestCostResolver.kt`:
```kotlin
internal data class RequestCost(val tokens: Long, val rule: String)

internal class RequestCostResolver(properties: RateLimitProperties) {
    fun resolve(request: HttpServletRequest): RequestCost
}
```
- Path: `PathContainer.parsePath(request.requestURI.removePrefix(request.contextPath))`, the same expression as
  `WideEventFilter`.
- Patterns are parsed once, in the constructor, with `PathPatternParser.defaultInstance`. An invalid pattern throws
  `PatternParseException`, which fails startup.
- A rule matches when its `method` is null or equals `request.method`, and its pattern matches the path. The first
  matching rule in list order wins: `RequestCost(rule.cost, "${rule.method ?: "*"} ${rule.pattern}")`. No match:
  `RequestCost(defaultCost, "default")`.
- `PathPattern` matches decoded segments, like Spring MVC routing, so `/v1/%6Deta` matches `/v1/meta`. A trailing
  slash does not match, and MVC does not route it either. Spring Security's `StrictHttpFirewall` has already rejected
  non-normalised paths (`//`, `;`, encoded `/` or `.`) before the limiter runs.

### 5. Bucket store (Redis, Bucket4j)
`platform/ratelimit/RedisRateLimiter.kt`, all `internal`:
```kotlin
sealed interface RateLimitDecision {
    data class Allowed(val remaining: Long) : RateLimitDecision
    data class Rejected(val remaining: Long, val retryAfterSeconds: Long) : RateLimitDecision
    data object Unavailable : RateLimitDecision
}

fun interface RateLimiter {
    fun tryConsume(key: ClientKey, cost: Long): RateLimitDecision
}

class RedisRateLimiter(
    private val proxyManager: () -> ProxyManager<String>?,   // LazyConnection::getOrNull in production
    properties: RateLimitProperties,
    private val nanoTime: () -> Long = System::nanoTime,       // only for the WARN throttle
) : RateLimiter

object RateLimitRedis {
    fun client(resources: ClientResources, details: DataRedisConnectionDetails, timeout: Duration): RedisClient
    fun proxyManager(client: RedisClient, timeout: Duration): ProxyManager<String>
}

/** `rl:<capacity>-<refillTokens>-<refillPeriodMillis>:`, e.g. `rl:120-120-60000:`. */
fun bucketKeyPrefix(properties: RateLimitProperties): String

/** Whole seconds, rounded up, at least 1: `max(1, Math.ceilDiv(nanosToWait, 1_000_000_000L))`. */
fun retryAfterSeconds(nanosToWait: Long): Long
```
`RateLimitRedis.client`:
- If `details.sentinel`, `details.cluster`, `details.masterReplica` or `details.sslBundle` is non-null, throw
  `IllegalStateException("Rate limiting supports a standalone Redis without TLS only (spring.data.redis.*)")`.
  In-cluster traffic gets mTLS from Linkerd (docs/PLAN.md §8.1). `details.standalone` must be non-null (`checkNotNull`).
- `RedisURI.builder().withHost(host).withPort(port).withDatabase(database).withTimeout(timeout)`. If `details.password`
  is non-empty: `withAuthentication(username, password)` when `details.username` is non-empty, else `withPassword(password)`.
- `RedisClient.create(resources, uri)`, then `setOptions(...)` with `ClientOptions.builder()`:
  `autoReconnect(true)`, `disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)` (no queueing while
  disconnected: fail at once), `socketOptions(SocketOptions.builder().connectTimeout(timeout).build())`,
  `timeoutOptions(TimeoutOptions.enabled(timeout))`.

`RateLimitRedis.proxyManager`:
`Bucket4jLettuce.casBasedBuilder(client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)))`
`.expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1)))`
`.requestTimeout(timeout).build()`. The expiration strategy puts a TTL on every bucket key (idle keys disappear about a
minute after they refill). Bucket4j's request timeout is one deadline over the whole compare-and-swap operation,
retries included.

`RedisRateLimiter`:
- Builds once: `BucketConfiguration.builder().addLimit { it.capacity(capacity).refillGreedy(refillTokens, refillPeriod) }.build()`
  and `bucketKeyPrefix(properties)`.
- `tryConsume`: inside `try`, `val manager = proxyManager() ?: return Unavailable`; then
  `manager.builder().build(prefix + key.value, configuration).tryConsumeAndReturnRemaining(cost)`. Consumed →
  `Allowed(probe.remainingTokens)`; else
  `Rejected(probe.remainingTokens, retryAfterSeconds(probe.nanosToWaitForRefill))`.
- `catch (e: Exception)` (add `@Suppress("TooGenericExceptionCaught")` and a one-line comment: fail open on any store
  error) → `Unavailable`, plus a WARN `"Rate limiting unavailable, failing open: {}"` with `e.javaClass.name` only (no
  message, no stack trace). At most one such WARN per 60 s: the first failure logs, later ones only when 60 s have passed
  since the last WARN (an `AtomicLong` of `nanoTime()` values).
- Why the key prefix carries the bucket configuration: Bucket4j keeps the configuration stored with a bucket, and an
  active client's key never expires, so a lowered capacity would never reach busy clients (an attacker included). A new
  configuration means new keys: every client starts once with a full bucket, and old keys expire by TTL.

`platform/ratelimit/LazyConnection.kt`:
```kotlin
/**
 * One lazily created connection. It is created on a caller's thread, by one thread at a time, and at most once per
 * [backoff] after a failure. Callers that would have to wait get null instead (the limiter then fails open).
 */
internal class LazyConnection<T : Any>(
    private val connect: () -> T,
    private val backoff: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun getOrNull(): T?
}
```
1. A stored value (`@Volatile`) is returned without locking.
2. Otherwise `lock.tryLock()` on a `ReentrantLock` (not `synchronized`: it pins virtual threads on Java 21). Not
   acquired → `null`.
3. Under the lock: re-check the value; if the last failed attempt was less than `backoff` ago → `null`; otherwise call
   `connect()`. Success → store and return. Failure → record the attempt time and rethrow (the limiter logs it).

Once connected, Lettuce reconnects by itself; while it is disconnected, commands fail at once (REJECT_COMMANDS).
Worst case added latency per request: about one `timeout` (Bucket4j deadline), or about two on the request that makes a
connect attempt (connect, then handshake).

### 6. The filter and where it sits
`platform/ratelimit/RateLimitFilter.kt`:
```kotlin
internal class RateLimitFilter(
    private val keys: ClientKeyResolver,
    private val costs: RequestCostResolver,
    private val limiter: RateLimiter,
) : OncePerRequestFilter()
```
- `shouldNotFilter`: `true` unless the path (§4 expression) matches the `PathPattern` `/v1/**`. Keep
  `OncePerRequestFilter`'s defaults that skip ERROR and ASYNC dispatches.
- `doFilterInternal`:
  1. `key = keys.resolve(request)`, `cost = costs.resolve(request)`.
  2. Wide event (`WideEvent.current()?`): `rate_limit.rule` = `cost.rule`, `rate_limit.cost` = `cost.tokens`,
     `rate_limit.key_type` = `key.type`.
  3. `limiter.tryConsume(key, cost.tokens)`:
     - `Allowed` → `rate_limit.outcome` = `ALLOWED`, `rate_limit.remaining`; call the chain.
     - `Rejected` → `rate_limit.outcome` = `REJECTED`, `rate_limit.remaining`; set `Retry-After`
       (`HttpHeaders.RETRY_AFTER`, the seconds as a string) **before** writing, then
       `ProblemResponses.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", "Rate limit exceeded.")`.
       Do not call the chain. No other headers.
     - `Unavailable` → `rate_limit.outcome` = `UNAVAILABLE`; call the chain.
  4. Use an `internal enum class RateLimitOutcome { ALLOWED, REJECTED, UNAVAILABLE }`; the wide event writes enums as
     lowercase names.

**Position (decision).** Inside Spring Security's chain, directly after `CorsFilter`, before every authentication and
authorization filter. Registered with a `Customizer<HttpSecurity>` bean (§7): Spring Security 7.1 applies every
`Customizer<HttpSecurity>` bean to the `HttpSecurity` before `SecurityConfiguration.apiSecurityFilterChain` configures
it (`HttpSecurityConfiguration.applyHttpSecurityCustomizers`). The filter object is created inside that bean method and
is **not** a Spring bean: Boot registers every `Filter` bean in the servlet container too, where it would run outside
the chain, before CORS.

| Option | Why not / why |
|---|---|
| `HandlerInterceptor` (old plan) | Runs after Spring Security. Today every `/v1` path except `GET /v1/meta` is `authenticated`, so unauthenticated floods get 401 without spending a token |
| Servlet filter before Spring Security, next to `RequestBodyLimitFilter` | Limits everything, but the 429 has no CORS headers (the web client cannot read `Retry-After`) and no hardened headers |
| **In the chain after `CorsFilter`** (chosen) | Limits unauthenticated traffic; the 429 gets CORS headers and `HeaderWriterFilter`'s headers; the firewall has already normalised the path |

Consequences, all intended:
- CORS preflights and CORS-rejected requests are answered by `CorsFilter` and cost no tokens (cheap, no Redis).
- `http.route` is `UNMATCHED` on a 429 because MVC never ran; `rate_limit.rule` says which rule applied.
- Bodies over 1 MB are still rejected first by `RequestBodyLimitFilter` (a servlet filter that runs before the
  security chain), with no Redis call.
- The management port uses the same chain for `/actuator/**`. The filter skips it (`/v1/**` only): probes are never limited.

**Later (not this task).** A session or user resolver replaces `IpClientKeyResolver`, for example a session key when a
valid signed session token is present, else the IP key. That task decides whether a second position after
authentication is needed. Note for that task: a resolver in another module needs `ClientKeyResolver` exposed (base
package or named interface), because `platform.ratelimit` is internal to the `platform` module.

### 7. Wiring
`platform/ratelimit/RateLimitConfiguration.kt`:
```kotlin
@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.API)
@ConditionalOnBooleanProperty("tastebuds.rate-limit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(RateLimitProperties::class)
internal class RateLimitConfiguration {
    @Bean(destroyMethod = "shutdown")
    fun rateLimitRedisClient(
        resources: ClientResources,          // Boot's shared lettuceClientResources; shutdown() leaves it running
        details: DataRedisConnectionDetails,
        properties: RateLimitProperties,
    ): RedisClient = RateLimitRedis.client(resources, details, properties.timeout)

    @Bean
    fun rateLimiter(
        rateLimitRedisClient: RedisClient,
        properties: RateLimitProperties,
    ): RateLimiter {
        val connection =
            LazyConnection(
                connect = { RateLimitRedis.proxyManager(rateLimitRedisClient, properties.timeout) },
                backoff = properties.reconnectBackoff,
            )
        return RedisRateLimiter(connection::getOrNull, properties)
    }

    @Bean
    fun clientKeyResolver(): ClientKeyResolver = IpClientKeyResolver()

    @Bean
    fun rateLimitSecurityCustomizer(
        keys: ClientKeyResolver,
        limiter: RateLimiter,
        properties: RateLimitProperties,
    ): Customizer<HttpSecurity> {
        val filter = RateLimitFilter(keys, RequestCostResolver(properties), limiter)
        // CorsFilter = org.springframework.web.filter.CorsFilter
        return Customizer { http -> http.addFilterAfter(filter, CorsFilter::class.java) }
    }
}
```
Declare the return type `Customizer<HttpSecurity>` explicitly, so Spring can match the generic type. Startup never
touches Redis: the first connection happens on the first limited request.

`SecurityConfiguration.kt`: add exactly one line, directly above `http {` in `apiSecurityFilterChain`, and nothing else:
```kotlin
        // Customizer<HttpSecurity> beans extend this chain: platform.ratelimit adds the rate limiter right after CorsFilter.
```

### 8. Wide event fields
Set only for `/v1/**` requests that reach the filter:

| Field | Type | Description |
|---|---|---|
| `rate_limit.outcome` | string | `allowed`, `rejected` (429) or `unavailable` (Redis unreachable or too slow; the request went through) |
| `rate_limit.cost` | long | Tokens this request costs |
| `rate_limit.remaining` | long | Tokens left in the client's bucket after this request; absent when `unavailable` |
| `rate_limit.rule` | string | Matching rule (`GET /v1/meta`, `* /v1/...`) or `default`. Needed because `http.route` is `UNMATCHED` on a 429 |
| `rate_limit.key_type` | string | `ip4`, `ip6` (the key is the /64) or `invalid` (unparsable address, one shared bucket) |

Never the client address or the key. Do not use `token` in a key name: `Redaction` force-redacts any key that
contains it.

### 9. Test infrastructure and local run
New `services/core/src/test/kotlin/dev/danya/tastebuds/core/RedisTestcontainersConfiguration.kt`:
```kotlin
/**
 * Redis for rate-limit tests and `bootTestRun`. One container per JVM, not per Spring context: tests share it and use
 * distinct client keys. Not a bean, so no closing context stops it; Testcontainers' Ryuk removes it when the JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestcontainersConfiguration {
    @Bean
    fun redisProperties(): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
        }

    /** Kotlin cannot use `GenericContainer<Nothing>` fluently (`Nothing` makes the next statement unreachable, an error here). */
    class RedisContainer(image: DockerImageName) : GenericContainer<RedisContainer>(image)

    companion object {
        const val REDIS_PORT = 6379

        val redis: RedisContainer by lazy {
            RedisContainer(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(REDIS_PORT).apply { start() }
        }
    }
}
```
(`redis:8.10.3-alpine` exists on Docker Hub as of 2026-11-20.)

`TestTastebudsCoreApplication.kt`: `.with(TestcontainersConfiguration::class, RedisTestcontainersConfiguration::class)`,
and the KDoc says "Testcontainers-managed Postgres and Redis". **Local run decision:** `bootTestRun` does not use the
`test` profile, so the limiter is on (production default) and uses the Testcontainers Redis. A plain `bootRun` without
Redis fails open with WARN lines; `bootTestRun` is the documented entry point.

Isolation: tests share one Redis. Every test uses its own client addresses (TEST-NET-2 `198.51.100.0/24`, TEST-NET-3
`203.0.113.0/24`, IPv6 `2001:db8::/32`) or its own key namespace, and never flushes Redis. `RateLimitIntegrationTest` is
the only new Spring test context (one more Postgres container); all other new tests run without Spring or with
`ApplicationContextRunner`.

### 10. Docs
- `docs/observability/WIDE_EVENT_SCHEMA.md`: after the `### Auth` section, add this section (the table rows are the
  five rows of §8, verbatim):
  ````markdown
  ### Rate limiting (`/v1/**` only)
  | Field | Type | Description |
  |---|---|---|
  <the five rows of §8>

  Never the client address or the bucket key (personal data).
  ````
  In "Reserved namespaces", delete the entry `rate_limit.*` together with its `(task 001)` note and the comma and space
  after it. Change nothing else.
- `SECURITY.md`, "Security design": after the `Postgres statement timeout (5 s); …` bullet, add this one line:
  ```markdown
  - Per-client rate limiting on `/v1/**`: cost-weighted token buckets in Redis, keyed by client IP (IPv6 by /64) and checked before authentication; over the limit: 429 problem+json with `Retry-After`. Fails open while Redis is unavailable
  ```
- `README.md`, the `bootTestRun` line: replace `(Postgres via Testcontainers)` with `(Postgres and Redis via Testcontainers)`.
  Change nothing else.

### Expected layout and size
Main code in `platform/ratelimit/`: `RateLimitProperties.kt`, `ClientKeys.kt`, `RequestCostResolver.kt`,
`RedisRateLimiter.kt`, `LazyConnection.kt`, `RateLimitFilter.kt`, `RateLimitConfiguration.kt`; about 400 lines with
imports and KDoc. Tests mirror it under the test `platform/ratelimit/` package.

## Acceptance criteria
1. **Dependencies.** The catalog has `bucket4j = "8.22.0"` and the two aliases; `services/core/build.gradle.kts` adds
   exactly the three dependencies of §1. `services/core/gradle.lockfile` is regenerated with the §1 command and is the
   only changed lockfile.
2. **Allowed.** A `/v1/**` request with enough tokens reaches its handler unchanged. Its wide event has
   `rate_limit.outcome=allowed`, `rate_limit.cost`, `rate_limit.remaining`, `rate_limit.rule`, `rate_limit.key_type`.
3. **Rejected.** A request that needs more tokens than remain gets `429`, `Content-Type: application/problem+json`, body
   `type=about:blank`, `title=Too Many Requests`, `status=429`, `detail=Rate limit exceeded.`, `instance=<request path>`,
   and `Retry-After` as whole seconds ≥ 1. Later filters and the handler do not run. The wide event has
   `rate_limit.outcome=rejected` and `http.response.status_code=429`.
4. **Costs.** The cost is the first matching rule (optional method + `PathPattern` on the decoded path), else
   `default-cost`. `rate_limit.rule` is `<METHOD or *> <pattern>` or `default`.
5. **Position.** The filter is in Spring Security's chain after `CorsFilter` and before `AnonymousAuthenticationFilter`
   and `AuthorizationFilter`, exactly once, and no bean of type `RateLimitFilter` exists. Unauthenticated requests to
   protected `/v1` paths spend tokens: once the bucket is empty they get 429 instead of 401.
6. **429 headers.** For an allowed origin, the 429 carries `Access-Control-Allow-Origin: <origin>` and an
   `Access-Control-Expose-Headers` that includes `Retry-After`. It also carries `X-Content-Type-Options: nosniff` and a
   `Cache-Control` that contains `no-store`.
7. **Client keys.** `keyFor` follows §3: IPv4 by full address, IPv6 by /64, IPv4-mapped as IPv4, zone ignored, host
   names never resolved, everything else (null, over 64 characters, ports, brackets, whitespace, CR/LF, non-ASCII
   digits, ambiguous IPv4) to the shared `ip:invalid` key. Every key matches `KEY_SHAPE`. The limiter never reads
   `X-Forwarded-For`. Different keys have independent buckets; addresses in one /64 share one; all invalid addresses
   share one.
8. **Scope.** Requests outside `/v1/**` (including `/actuator/**` and `/error`), CORS preflights and CORS-rejected
   requests never reach the limiter: no Redis call and no `rate_limit.*` fields.
9. **Distributed and bounded.** Two limiter instances against the same Redis share bucket state. Every bucket key has a
   TTL and the form `rl:<capacity>-<refillTokens>-<refillPeriodMillis>:<client key>`.
10. **Fail open.** When Redis refuses connections, does not answer, or is inside the reconnect backoff, the request
    continues and its wide event has `rate_limit.outcome=unavailable` (no `remaining`). A non-answering Redis delays the
    decision by at most about the configured timeout (test: under 2 s at 250 ms). At most one connect attempt is made
    per `reconnect-backoff`. Failures log at most one WARN per minute, with the exception class name only. The
    application starts with Redis unreachable (nothing connects at startup).
11. **Config.** Properties, defaults and YAML as in §2. Each invalid value in the §2 table fails startup with a message
    that starts with the property name. Sentinel, Cluster, master/replica or TLS settings fail startup with the §5 message.
12. **Conditions.** With `tastebuds.rate-limit.enabled=false` or role `worker`, none of the §7 beans exists (no Redis
    client, no customizer, no limiter). With role `api` or `all`, or without the property, they exist.
13. **Tests and local run.** Rate limiting is off in the `test` profile. The Redis container is one per JVM and is not a
    bean. `RateLimitIntegrationTest` is the only new Spring test context. `bootTestRun` starts Postgres and Redis.
14. **Privacy.** The client address and the bucket key never appear in a response, a wide event or a log line.
15. **Docs.** WIDE_EVENT_SCHEMA.md, SECURITY.md, README.md and the `SecurityConfiguration` comment match §7 and §10
    exactly; nothing else changes in those files.

## Required tests
All under `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/`. Test names are the sentences below.

- `IpClientKeyResolverTest` (unit, AC 7, 14):
  - `IPv4 addresses key by the full address` (`203.0.113.7` → `ip4:203.0.113.7`, type `IP4`)
  - `IPv6 addresses key by their 64-bit prefix` (`2001:db8:85a3::8a2e:370:7334` and `2001:DB8:85A3:0:ffff::1` →
    `ip6:2001:db8:85a3:0::/64`; `2001:db8:85a3:1::1` → a different key)
  - `IPv4-mapped IPv6 addresses key as IPv4` (`::ffff:203.0.113.7` and `::ffff:cb00:7107` → `ip4:203.0.113.7`)
  - `zone ids are ignored` (`fe80::1%eth0` → `ip6:fe80:0:0:0::/64`)
  - `host names are never resolved` (negative: `localhost`, `example.com`, `cafe`, `deadbeef` → `ip:invalid`)
  - `malformed addresses fall back to one shared key` (negative: `null`, `""`, `not-an-ip`, `1.2.3`, `256.1.1.1`,
    `010.0.0.1`, `1.2.3.4:80`, `[2001:db8::1]`, `2001:db8::1::2`, `.:1`, `" 203.0.113.7"`, `"203.0.113.7\n"`,
    `"203.0.113.7\r\nX-Injected: 1"`, `"１２３.0.0.1"` (full-width digits), `"1:".repeat(32) + "1"` (65 characters),
    a 10 000-character string → `ip:invalid`, type `INVALID`)
  - `keys always have a bounded, known shape` (10 000 strings from `Random(42)`, lengths 0..80, characters from
    `0-9 a-f A-F : . % [ ] space \n é １`: every key matches `KEY_SHAPE` and has ≤ 28 characters)
  - `the resolver reads remoteAddr and ignores X-Forwarded-For` (negative: `MockHttpServletRequest` with
    `remoteAddr = 203.0.113.7` and `X-Forwarded-For: 198.51.100.9` → `ip4:203.0.113.7`)
- `RequestCostResolverTest` (unit, AC 4): `the first matching rule sets the cost`;
  `a rule with a method matches only that method`; `path variables in patterns match` (`/v1/media/{id}` vs
  `/v1/media/42`); `paths match after decoding, like Spring MVC routing` (`/v1/%6Deta`);
  `unmatched requests use the default cost` (including `/v1/meta/`); `an invalid pattern fails construction` (`/v1/{`).
- `RateLimitPropertiesTest` (unit, AC 9, 11): `defaults are valid` (values of §2; `bucketKeyPrefix` = `rl:120-120-60000:`);
  `invalid values are rejected` (one `assertFailsWith<IllegalArgumentException>` per row of the §2 table, both bounds
  where there are two, plus lower-case `get` and `FOO` methods and a `/actuator/health` pattern; each message starts
  with the property name); `the bucket key prefix changes with the bucket configuration`.
- `LazyConnectionTest` (unit, fake clock, counting `connect`, AC 10): `a successful connection is created once and reused`;
  `a failed attempt rethrows, then returns null without retrying until the backoff has passed`;
  `after the backoff a new attempt is made`; `callers do not wait while another thread connects` (the `connect` lambda
  blocks on a latch in a second thread; the main thread gets `null` at once).
- `RedisRateLimiterTest` (real Redis via `RedisTestcontainersConfiguration.redis`, no Spring; `DefaultClientResources`
  created in `@BeforeAll`, shut down in `@AfterAll`; AC 2, 3, 7, 9):
  - `requests within capacity are allowed and report the remaining tokens`
  - `the request that exceeds the bucket is rejected with a retry delay` (capacity 3, refill 3 per 1 h: cost 2 →
    `Allowed(1)`; cost 2 → `Rejected(1, s)` with `s` in 1..3600)
  - `different client keys have independent buckets`
  - `two limiter instances share bucket state through Redis` (two clients, two `LazyConnection`s, one key)
  - `bucket keys expire when idle` (raw Lettuce `PTTL` of `rl:...` > 0)
  - `the Lettuce client fails fast and is time-bounded` (`client.options`: `REJECT_COMMANDS`, timeout commands on,
    `socketOptions.connectTimeout` = the configured timeout)
  - `retry-after rounds up to whole seconds, at least one` (`retryAfterSeconds`: 0 → 1, 1 → 1, 1e9 → 1, 1e9 + 1 → 2)
- `RedisRateLimiterFailOpenTest` (no Docker, `OutputCaptureExtension`, AC 10, 14):
  - `an unreachable Redis means unavailable, without waiting` (port from a closed `ServerSocket(0)`; `Unavailable` in under 2 s)
  - `a Redis that never answers means unavailable within the timeout` (an open `ServerSocket(0)` that never accepts;
    timeout 250 ms; `Unavailable` in under 2 s)
  - `no connection means unavailable` (supplier returns `null`)
  - `failures log at most one warning per minute, without the message` (supplier throws
    `IllegalStateException("secret-marker")`; two failures at the same fake time → exactly one WARN line with
    `java.lang.IllegalStateException` and without `secret-marker`; after the fake clock moves 61 s, a second WARN)
- `RateLimitFilterTest` (unit: fake `RateLimiter`, `MockHttpServletRequest`/`MockHttpServletResponse`/`MockFilterChain`,
  `WideEvent.within`; AC 2, 3, 8, 14):
  - `allowed requests continue and record the outcome`
  - `rejected requests get a 429 problem with Retry-After and stop` (chain not called; body fields; header; neither the
    body nor any wide event value contains the client address `198.51.100.23`)
  - `when the store is unavailable requests continue and the outcome says so`
  - `paths outside v1 are never limited` (`/actuator/health/liveness`, `/error`, `/`: limiter not called, no
    `rate_limit.*` fields)
- `RateLimitIntegrationTest` (`@SpringBootTest(properties = [...])`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`,
  `@Import(TestcontainersConfiguration::class, RedisTestcontainersConfiguration::class)`,
  `@ExtendWith(OutputCaptureExtension::class)`. Properties: `tastebuds.rate-limit.enabled=true`, `capacity=3`,
  `refill-tokens=3`, `refill-period=1h`, `rules[0].method=GET`, `rules[0].pattern=/v1/meta`, `rules[0].cost=2` (all
  three rule fields: a list from a higher-priority source replaces the YAML list), and
  `tastebuds.cors.allowed-origins=https://app.example`. Set the client with `with { it.remoteAddr = "..."; it }`; parse
  wide events from the captured output as `ErrorResponsesIntegrationTest` does; dotted keys are nested JSON, e.g.
  `/rate_limit/outcome`):
  - `rule costs apply and the request over the limit gets a 429 problem` (`GET /v1/meta` → 200, then 429 with the AC 3
    body and header; wide events: `allowed`, cost 2, remaining 1, rule `GET /v1/meta`, key type `ip4`, then
    `rejected` with status 429; no `wide_event` line contains the client address) (AC 2, 3, 4, 14)
  - `unauthenticated requests are limited before authentication` (negative: `GET /v1/anything` three times → 401,
    fourth → 429; rule `default`) (AC 5)
  - `clients have independent buckets` (client A empty → client B still 200) (AC 7)
  - `unparsable client addresses share one bucket` (negative: empty the bucket with `remoteAddr = not-an-ip` → a request
    with `remoteAddr = 999.1.1.1` gets 429) (AC 7)
  - `a 429 is readable by allowed browser origins and carries the hardened headers` (`Origin: https://app.example`) (AC 6)
  - `preflights and rejected cross-origin requests cost nothing` (from one client: five preflights
    `OPTIONS /v1/meta` with `Origin: https://app.example` and `Access-Control-Request-Method: GET`, and five
    `GET /v1/meta` with `Origin: https://evil.example` (403); then a plain `GET /v1/meta` → 200 with remaining 1; none of
    the preflight or 403 wide events has a `rate_limit` object) (AC 8)
  - `the limiter runs in the security chain after CORS and before authentication` (from `FilterChainProxy.filterChains`:
    exactly one `RateLimitFilter`, index after `CorsFilter`, before `AnonymousAuthenticationFilter` and
    `AuthorizationFilter`; `getBeanNamesForType(RateLimitFilter::class.java)` is empty) (AC 5)
- `RateLimitConfigurationTest` (`ApplicationContextRunner` with `AutoConfigurations.of(DataRedisAutoConfiguration::class.java)`,
  user configuration `RateLimitConfiguration`, `spring.data.redis.port` = a closed port; AC 10, 11, 12):
  `the api role gets the limiter without connecting to Redis at startup`;
  `the all role and a missing enabled property also get the limiter`; `the worker role gets no limiter`;
  `disabling rate limiting removes the limiter`; `invalid rate-limit settings fail startup` (`default-cost=0`);
  `unsupported Redis topologies and TLS fail startup` (`spring.data.redis.cluster.nodes=localhost:7000`;
  `spring.data.redis.ssl.enabled=true`).

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- Kover line coverage of `dev.danya.tastebuds.core.platform.ratelimit` ≥ **80%** (global floor: 70%).
- Wide event fields of §8 are documented in docs/observability/WIDE_EVENT_SCHEMA.md.
- No dependency beyond §1; only `services/core/gradle.lockfile` changes among lockfiles.
- `scripts/agentic/scope.sh diff docs/tasks/001-rate-limiting.md main` reports no violation.

## Note for the user (not part of the implementation)
After merge, `CLAUDE.md` (Commands, `bootTestRun` line) still says "Testcontainers-managed Postgres"; it should say
"Postgres and Redis". Agents do not edit CLAUDE.md, so this task leaves it.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->

### Round 1: APPROVE
Orchestrator note: the implementer first stopped with SCOPE_BLOCKED because the documented root lock command rewrote the
line-4 header comment of `build-logic/gradle.lockfile` (no dependency lines). The orchestrator restored the file with
`git checkout` and filed `docs/backlog/2026-11-20-root-lock-command-rewrites-build-logic-header.md`; the implementer
then completed the round.

Findings: none blocking. All 15 acceptance criteria verified by the reviewer; `./gradlew :services:core:test --rerun
check koverXmlReport` green (21 test classes, 0 failures); Kover `platform.ratelimit` 185/193 lines (95.9%);
`scope.sh diff` clean; `bootTestRun` verified by hand (Postgres + Redis start, limits apply, no client address in wide
events). Implementer deviations 1–5 accepted.

Optional nits (not required):
1. `RateLimitIntegrationTest.kt:180`: the CORS-order assertion also passes when `CorsFilter` is missing (`indexOfFirst` = -1).
2. `RedisRateLimiterTest.kt:31-36`: per-test `RedisClient`s are never shut down (closed with the shared `ClientResources`).
3. `gradle/libs.versions.toml:13`: `bucket4j` sits under the freemarker "Build-logic override" comment.

Backlog suggestions filed: `2026-11-20-redis-health-contributor-all-roles.md`, `2026-11-20-lettuce-reconnect-log-volume.md`.
