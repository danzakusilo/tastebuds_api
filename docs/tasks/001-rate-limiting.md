# 001 — Cost-weighted rate limiting (Redis + Bucket4j)

- **Status:** READY
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-15
- **Depends on:** none

## Goal
Protect the API against abuse and cost-DoS with distributed token-bucket rate limiting (docs/PLAN.md §8.2).
Each request costs tokens according to its endpoint (search is cheap, deep matching expensive later). Buckets are
keyed per client (IP until authentication exists). State lives in Redis so limits hold across `api` replicas.
Over-limit requests get `429` problem+json with `Retry-After`; every request's wide event records the outcome.

## Non-goals
- Per-user or per-anonymous-session keys (those principals don't exist yet; the key resolver is an interface so they can be added later).
- Ingress/Cloudflare-level limiting, login throttling (identity service), CAPTCHA.
- Applying costs to endpoints that don't exist yet. Only the cost table mechanism and `/v1/meta`.
- Any change to security headers, CORS, or the wide event library.

## Allowed paths
- `services/core/build.gradle.kts`
- `gradle/libs.versions.toml`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/ratelimit/**`
- `services/core/src/main/resources/application.yaml`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/ratelimit/**`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/TestcontainersConfiguration.kt`
- `services/core/src/test/resources/application-test.yaml`
- `docs/observability/WIDE_EVENT_SCHEMA.md`

## Design notes
- **Dependencies:** `org.springframework.boot:spring-boot-starter-data-redis` (BOM-managed);
  `com.bucket4j:bucket4j_jdk17-core` and `com.bucket4j:bucket4j_jdk17-lettuce`, version `8.22.0` (add `bucket4j` to the
  catalog `[versions]` and both libraries to `[libraries]`); test: `com.redis:testcontainers-redis` (BOM-managed).
- **Config** (`tastebuds.rate-limit.*`, a `@ConfigurationProperties` data class `RateLimitProperties` in `platform.ratelimit`):
  ```yaml
  tastebuds:
    rate-limit:
      enabled: true
      capacity: 120            # tokens per bucket
      refill-per-minute: 120   # greedy refill
      default-cost: 1
      costs:                   # route pattern (Spring MVC best-matching pattern) -> tokens
        "/v1/meta": 1
      trusted-proxy-count: 0   # how many X-Forwarded-For hops to trust (0 = use remoteAddr only)
  ```
- **Types** (all `internal` except the properties class):
  - `fun interface ClientKeyResolver { fun resolve(request: HttpServletRequest): String }`, default `IpClientKeyResolver`:
    with `trusted-proxy-count = n > 0`, take the n-th address from the right of `X-Forwarded-For`; otherwise `remoteAddr`.
    Never trust the header when n = 0 (spoofing). Key format: `rl:ip:<address>`.
  - `RateLimitInterceptor : HandlerInterceptor`. Runs in `preHandle` (route pattern is known there via
    `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`). It consumes `cost` tokens with
    `bucket.tryConsumeAndReturnRemaining(cost)` and sets the `RateLimit-Limit`, `RateLimit-Remaining` headers.
    On rejection it throws `RateLimitExceededException(retryAfterSeconds: Long)`.
  - Registered via a `WebMvcConfigurer` in `platform.ratelimit` for `/v1/**`, only when `tastebuds.rate-limit.enabled=true`
    and the role is API (`@ConditionalOnRole(Role.API)`).
  - Bucket storage: Bucket4j `LettuceBasedProxyManager` with a `byte[]` codec, expiration
    `ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(5))` so idle keys expire.
- **Error mapping:** a `@RestControllerAdvice` in `platform.ratelimit` with `@Order(Ordered.HIGHEST_PRECEDENCE)` handles
  `RateLimitExceededException` → `429`, problem+json title `Too Many Requests`, detail `Rate limit exceeded.`,
  header `Retry-After: <seconds>` (ceil, minimum 1). Do not modify `ProblemDetailsAdvice`.
- **Fail-open:** if Redis is unavailable, allow the request, put `rate_limit.outcome = "unavailable"` on the wide event,
  and log one WARN per failure (no stack trace spam). Availability over strictness, because the edge layer (Cloudflare) is the
  first line of defence in production.
- **Wide event fields:** `rate_limit.cost` (int), `rate_limit.remaining` (long), `rate_limit.outcome`
  (`allowed` | `rejected` | `unavailable`). Never the client IP or key.

## Acceptance criteria
1. Requests under the limit succeed and carry `RateLimit-Limit` and `RateLimit-Remaining` headers.
2. The request that exceeds the bucket gets `429`, `application/problem+json`, and a `Retry-After` ≥ 1.
3. Costs come from config by route pattern; unknown routes use `default-cost`.
4. Two different client keys have independent buckets.
5. `X-Forwarded-For` is ignored when `trusted-proxy-count = 0`, and used correctly when it is 1.
6. With Redis down, requests are allowed and the wide event says `rate_limit.outcome=unavailable`.
7. With `tastebuds.rate-limit.enabled=false` or role `worker`, no interceptor is registered.
8. Wide event fields above are emitted and documented in WIDE_EVENT_SCHEMA.md.
9. Buckets survive across two separate `ProxyManager` instances against the same Redis (i.e., state is distributed, not in-memory).

## Required tests
- `IpClientKeyResolverTest` (unit): remoteAddr default; spoofed XFF ignored at n=0; n=1 picks the right-most hop; malformed header falls back to remoteAddr.
- `RateLimitIntegrationTest` (`@SpringBootTest` + MockMvc + Redis Testcontainer, small capacity via `@TestPropertySource`):
  criteria 1, 2, 3, 4, 8 (assert the wide event fields from captured JSON output, as `CoreApplicationIntegrationTest` does).
- `RateLimitDistributedStateTest`: criterion 9.
- `RateLimitFailOpenTest`: criterion 6 (point Redis at an unused port; assert 200 and the outcome field).
- `RateLimitConfigurationTest` (`ApplicationContextRunner`): criterion 7.

## Quality gates
- `./gradlew check` passes.
- Kover line coverage of `platform.ratelimit` ≥ 80%.
- No new dependency beyond those in Design notes.

## Review log
<!-- Appended by the run-task orchestrator. -->
