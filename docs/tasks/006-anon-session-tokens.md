# 006 — Anonymous sessions: signed bearer tokens, `POST /v1/sessions/anonymous`, authentication

- **Status:** READY
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-20
- **Depends on:** 001 (DONE)

## Decisions (answered 2026-11-20)
1. **Token format:** own compact format `tba1.<kid>.<payload>.<mac>`, HMAC-SHA256 with `javax.crypto`, no new
   dependency. Keys from a secret through env variables, a list for rotation (index 0 signs, all verify). A random
   per-start key only for tests and `bootTestRun` (§3, §4).
2. **Lifetime:** 30 days from creation, absolute, no renewal. Expired tokens get the same 401 as forged ones (§3, §6).
   No server-side revocation until Phase 4.
3. **Budgets:** no budget code in Phase 1. Per-session daily LLM budgets (docs/PLAN.md §5.6) come with their first
   consumer in Phase 3. The orchestrator updates the Phase 1 line of docs/PLAN.md §11; this task does not touch PLAN.md.
4. **Rate limiting:** the session key adds a second bucket next to the IP bucket (task 007).
5. Planner decisions confirmed: token code in `platform`, the endpoint in a new `sessions` module (§1);
   `anyRequest` → `hasRole("USER")` (§7); 401 for bad tokens on every path (§6).

## Goal
Signed-out users get an anonymous session (docs/PLAN.md §3.2 `sessions`, §4 `AnonSession`, D4; tastebuds_spec F01 §6:
`POST /v1/sessions/anonymous` on the first tap of the demo field, token kept in secure storage). The session is a
signed bearer token that carries its id and expiry, so the API verifies it without I/O: authentication keeps working
while Redis is down, and the rate limiter (task 007) can key by session before authentication. Requests with a valid
token are authenticated as `ANON_SESSION`; invalid or expired tokens get a uniform 401. Anonymous sessions open only
endpoints that say so: unlisted paths need a signed-in account (Phase 4). Phase 2 endpoints (`/v1/me/takes/**`) then
add their own `ANON_SESSION` rules.

## Non-goals
- Per-session budgets (decision 3), revocation or a denylist, renewal or refresh, logout, merge-on-signup (Phase 4).
- Rate-limit buckets per session: task 007. This task adds only the cost rule for the new endpoint (§8).
- Any endpoint that requires `ANON_SESSION` (takes, matches: Phases 2–3). No `GET` for sessions.
- OAuth2 resource server, JWT, identity tokens (Phase 4). No new dependency.
- Storing sessions anywhere (Redis, Postgres). No Flyway migration.
- `Idempotency-Key` on the new endpoint: it stores nothing, so a retried request only issues one more token.
- OpenAPI generation (not set up yet).
- The generated in-memory user (`docs/backlog/2026-11-18-unused-generated-security-user.md`) and the Boot 4 security
  test glue (`docs/backlog/2026-11-18-boot4-security-test-glue.md`). Tests send real tokens instead of `@WithMockUser`.
- Helm, k8s, compose or any `deploy/**` change (the Helm chart task supplies the key secret).
- `ProblemResponses`, `ProblemDetailsAdvice`, the CORS configuration, the rate-limit code, `CLAUDE.md`.

## Allowed paths
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/AnonSessions.kt`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/security/AnonSession*.kt`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/security/BearerCredentials.kt`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/security/SecurityConfiguration.kt`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/sessions/**`
- `services/core/src/main/resources/application.yaml`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/security/AnonSession*.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/security/BearerCredentialsTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/sessions/**`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/TestTastebudsCoreApplication.kt`
- `services/core/src/test/resources/application-test.yaml`
- `docs/observability/WIDE_EVENT_SCHEMA.md`
- `docs/security/ANON_SESSIONS.md`
- `SECURITY.md`

## Design notes

### 1. Where the code lives (module boundaries)
- `platform` (cross-cutting security) owns the token format, verification and request authentication. The rate limiter
  (`platform.ratelimit`, task 007) uses the verifier inside the same module, so `ClientKeyResolver` stays internal.
- `sessions` (new module, docs/PLAN.md §3.2) owns the session lifecycle: the endpoint now, merge-on-signup and budgets
  later. It uses only public types from the `platform` base package (`AnonSessionIssuer`, `IssuedAnonSession`,
  `ConditionalOnRole`, `Role`). Never import `platform.security` or `platform.web` from `sessions`.
- Why not everything in `sessions`: `platform.security` and `platform.ratelimit` would then depend on `sessions`, and
  `sessions` already depends on `platform` (`ConditionalOnRole`): a module cycle, which Modulith verification rejects.

### 2. Public API (`platform/AnonSessions.kt`, package `dev.danya.tastebuds.core.platform`)
```kotlin
/** The principal of a request authenticated with an anonymous session token (docs/PLAN.md §4 `AnonSession`). */
data class AnonSessionPrincipal(
    /** Internal identity. Never shown to other users; never in wide events, logs or responses. */
    val sessionId: UUID,
    /** Whole seconds; the token stops working at this instant. */
    val expiresAt: Instant,
)

/** A newly created anonymous session and its bearer token. */
class IssuedAnonSession(
    val principal: AnonSessionPrincipal,
    val token: String,
) {
    override fun toString(): String = "IssuedAnonSession(expiresAt=${principal.expiresAt}, token=[REDACTED])"
}

/** Creates anonymous sessions. A bean only in the `api` role. */
fun interface AnonSessionIssuer {
    fun issue(): IssuedAnonSession
}
```
Later endpoints read the principal with `@AuthenticationPrincipal principal: AnonSessionPrincipal`.

### 3. Token format v1 (`platform/security/AnonSessionTokens.kt`)
`tba1.<kid>.<payload>.<mac>` ("tastebuds anonymous, version 1"):

| Part | Content |
|---|---|
| `tba1` | Format version. A new format gets a new prefix |
| `<kid>` | Id of the signing key, `[a-z0-9]{1,16}`, in clear so verification can pick the key |
| `<payload>` | Base64url without padding (`Base64.getUrlEncoder().withoutPadding()`) of 24 bytes, big-endian: session id most significant 64 bits, least significant 64 bits, `expiresAt` epoch seconds (signed 64 bit). Always 32 characters |
| `<mac>` | Base64url without padding of HMAC-SHA256(key, US-ASCII bytes of `tba1.<kid>.<payload>`). Always 43 characters |

Longest token: 98 characters. Example shape: `tba1.k1.AZNb…(32).x9Qe…(43)`.

```kotlin
internal enum class InvalidTokenReason { MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, EXPIRED }

internal sealed interface AnonSessionVerification {
    data class Valid(val principal: AnonSessionPrincipal) : AnonSessionVerification
    data class Invalid(val reason: InvalidTokenReason) : AnonSessionVerification
}

internal class AnonSessionTokens(
    keys: List<Key>,                          // index 0 signs; every key verifies
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : AnonSessionIssuer {
    /** [secret] is raw key material, at least 32 bytes. */
    class Key(val id: String, secret: ByteArray) {
        override fun toString(): String = "Key(id=$id)"
    }

    override fun issue(): IssuedAnonSession
    fun verify(token: String): AnonSessionVerification

    companion object {
        const val PREFIX = "tba1"
        const val MAX_TOKEN_LENGTH = 128
        fun fromProperties(properties: AnonSessionProperties, clock: Clock = Clock.systemUTC()): AnonSessionTokens
    }
}
```
- Constructor: `require(keys.isNotEmpty())`, ids unique and matching `^[a-z0-9]{1,16}$`, every secret ≥ 32 bytes.
  `Key` keeps a `SecretKeySpec(secret.copyOf(), "HmacSHA256")`. A new `Mac.getInstance("HmacSHA256")` per call (`Mac`
  is not thread-safe; no `ThreadLocal`, which wastes memory with virtual threads).
- `issue()`: `sessionId = UUID.randomUUID()` (122 random bits from `SecureRandom`; the id is internal, so no UUIDv7);
  `expiresAt = Instant.ofEpochSecond(clock.instant().epochSecond + ttl.seconds)`; sign with key 0. The returned
  principal equals what `verify` returns for the token.
- `verify(token)`, in this order, never throwing:
  1. `token.length > MAX_TOKEN_LENGTH` → `MALFORMED`.
  2. `Regex("^tba1\\.([a-z0-9]{1,16})\\.([A-Za-z0-9_-]{32})\\.([A-Za-z0-9_-]{43})$").matchEntire(token)`; no match →
     `MALFORMED`.
  3. No key with that id → `UNKNOWN_KEY`.
  4. Compute the MAC of `tba1.<kid>.<payload>` and encode it; compare the encoded string with group 3 using
     `MessageDigest.isEqual` on the US-ASCII bytes (constant time; comparing encoded strings also rejects non-canonical
     Base64). Not equal → `BAD_SIGNATURE`. The payload is not decoded before this step.
  5. Decode the payload (cannot fail after step 2), build the principal.
  6. `!clock.instant().isBefore(expiresAt)` → `EXPIRED`. Otherwise `Valid(principal)`.
- `fromProperties`: if `signingKeys` is non-empty, use them (`Base64.getDecoder().decode(secret)`; the properties are
  already validated). Else if `ephemeralSigningKey`, use one key with id `ephemeral` and 32 bytes from `SecureRandom`,
  and log one WARN: `"Anonymous sessions use an ephemeral signing key: tokens stop working on restart and are not
  shared between replicas. Local development and tests only."` Else throw
  `IllegalStateException("tastebuds.anon-session.signing-keys must contain at least one key (set tastebuds.anon-session.ephemeral-signing-key=true for local development only)")`.

### 4. Configuration (`platform/security/AnonSessionProperties.kt`)
```kotlin
@ConfigurationProperties("tastebuds.anon-session")
data class AnonSessionProperties(
    /** Lifetime of a session from its creation. There is no renewal. */
    val ttl: Duration = Duration.ofDays(30),
    /** HMAC-SHA256 keys. Index 0 signs new tokens; every listed key verifies (rotation). */
    val signingKeys: List<SigningKey> = emptyList(),
    /** Use a random key per start when [signingKeys] is empty. Local development and tests only. */
    val ephemeralSigningKey: Boolean = false,
) {
    data class SigningKey(
        val id: String,
        /** Standard Base64 (with padding, one line) of at least 32 random bytes. */
        val secret: String,
    ) {
        override fun toString(): String = "SigningKey(id=$id, secret=[REDACTED])"
    }
}
```
Validation in `init` with `require`, messages start with the property name and never contain a secret:

| Property | Valid | Message |
|---|---|---|
| `ttl` | 1 h ..90 d | `tastebuds.anon-session.ttl must be between 1h and 90d` |
| `signing-keys[n].id` | `^[a-z0-9]{1,16}$` | `tastebuds.anon-session.signing-keys[n].id must match [a-z0-9]{1,16}` |
| `signing-keys[n].id` | unique | `tastebuds.anon-session.signing-keys[n].id must be unique` |
| `signing-keys[n].secret` | `Base64.getDecoder()` decodes it to ≥ 32 bytes | `tastebuds.anon-session.signing-keys[n].secret must be Base64 of at least 32 bytes` |

"At least one key" is **not** checked here: `@ConfigurationPropertiesScan` binds this class in every role, and the
`worker` role must start without the secret (least privilege). `fromProperties` checks it, in the `api` role only.

`application.yaml`, under `tastebuds:`, directly after the `web:` block (before `rate-limit:`):
```yaml
  anon-session:
    # Signed bearer tokens for signed-out users, docs/security/ANON_SESSIONS.md. Signing keys come from a secret, never
    # from this file: TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_ID / TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_SECRET (Base64,
    # at least 32 bytes; index 0 signs, every listed key verifies). Required in the api role.
    ttl: 30d                      # absolute lifetime from creation; no renewal
    ephemeral-signing-key: false  # true only for local development and tests: a random key per start
```
`application-test.yaml`: the `tastebuds:` block becomes exactly:
```yaml
tastebuds:
  rate-limit:
    # Off unless a test turns it on and imports RedisTestcontainersConfiguration: other contexts have no Redis.
    enabled: false
  anon-session:
    # A random signing key per test context, so no key is ever committed. Tests that mint tokens set their own keys.
    ephemeral-signing-key: true
```
`TestTastebudsCoreApplication.kt`: `.run("--tastebuds.anon-session.ephemeral-signing-key=true", *args)`; the KDoc
becomes "Local development entry point: the real application wired to Testcontainers-managed Postgres and Redis, with
an ephemeral anonymous-session signing key."

**No key material in the repo, tests included.** Tests generate secrets at runtime
(`ByteArray(32).also { SecureRandom().nextBytes(it) }`); gitleaks scans the repo.

### 5. Bearer credentials (`platform/security/BearerCredentials.kt`)
```kotlin
internal sealed interface BearerCredential {
    data object Absent : BearerCredential
    data object Malformed : BearerCredential
    class Present(val token: String) : BearerCredential {
        override fun toString(): String = "Present(token=[REDACTED])"
    }
}

internal object BearerCredentials {
    fun from(request: HttpServletRequest): BearerCredential
}
```
Rules (task 007 reuses them):
1. No `Authorization` header → `Absent`. More than one (`request.getHeaders`) → `Malformed`.
2. A value that does not start with `bearer` (ignoring case) → `Absent`: other schemes are ignored, like Spring's
   `DefaultBearerTokenResolver`; `httpBasic` is disabled, so they are never honoured.
3. Otherwise the whole value must match `^bearer +([A-Za-z0-9\-._~+/]+=*)$` (ignoring case, RFC 6750 `b64token`) →
   `Present(group 1)`, else `Malformed` (`Bearer`, `Bearer `, `Bearer a b`, `Bearerabc`).
4. Never read a token from the query string, a form field, the body or a cookie.

### 6. Authentication (`platform/security/AnonSessionAuthentication.kt`)
```kotlin
internal const val ANON_SESSION_AUTHORITY = "ANON_SESSION"

internal class AnonSessionAuthentication(
    private val principal: AnonSessionPrincipal,
) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority(ANON_SESSION_AUTHORITY))) {
    init { isAuthenticated = true }
    override fun getPrincipal(): AnonSessionPrincipal = principal
    override fun getCredentials(): Any? = null   // the token is not kept after verification
}

/** Not a Spring bean: it lives in the security chain only (same reason as RateLimitFilter). */
internal class AnonSessionAuthenticationFilter(
    private val tokens: AnonSessionTokens,
) : OncePerRequestFilter()
```
(`AbstractAuthenticationToken(Collection)` is not deprecated in Spring Security 7.1.2, checked.)

`doFilterInternal`, by `BearerCredentials.from(request)`:
- `Absent` → continue the chain unauthenticated (`AnonymousAuthenticationFilter` follows).
- `Present` and `verify` is `Valid` → `val strategy = SecurityContextHolder.getContextHolderStrategy()`; set a new empty
  context holding `AnonSessionAuthentication(principal)`; `WideEvent.current()?.put("principal.tier", "anon")`;
  continue the chain. Nothing is stored (stateless chain).
- `Malformed`, or `Present` with `Invalid(reason)` → reject, do not continue:
  `WideEvent.current()?.put("auth.failure", "invalid_token")?.put("auth.failure_reason", reason)` (the reason enum
  becomes its lowercase name, `MALFORMED` for `Malformed`); set `WWW-Authenticate: Bearer error="invalid_token"`
  before writing; `ProblemResponses.write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized", "The bearer token is invalid or expired.")`.
  The same status, header and body for every reason (anti-enumeration, docs/PLAN.md §8.2).
- The filter applies to every path of the chain, `permitAll` paths included (as Spring's resource server does): a
  client that sends a dead token learns it at once. Keep `OncePerRequestFilter`'s defaults (skip ERROR and ASYNC
  dispatches).
- Every `Bearer` credential is treated as an anonymous session token. Phase 4 adds a dispatch for account tokens (by
  prefix: `tba1.` vs JWT).

### 7. Wiring and authorization
`platform/security/AnonSessionConfiguration.kt`:
```kotlin
@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.API)
@EnableConfigurationProperties(AnonSessionProperties::class)
internal class AnonSessionConfiguration {
    @Bean
    fun anonSessionTokens(properties: AnonSessionProperties): AnonSessionTokens = AnonSessionTokens.fromProperties(properties)

    @Bean
    fun anonSessionSecurityCustomizer(tokens: AnonSessionTokens): Customizer<HttpSecurity> {
        val filter = AnonSessionAuthenticationFilter(tokens)
        return Customizer { http -> http.addFilterBefore(filter, AnonymousAuthenticationFilter::class.java) }
    }
}
```
`AnonSessionTokens` implements `AnonSessionIssuer`, so `sessions` injects the interface. Position: after `CorsFilter`
and the rate limiter (task 001 puts it right after `CorsFilter`), before `AnonymousAuthenticationFilter`, so floods of
forged tokens spend rate-limit tokens before any verification.

`SecurityConfiguration.kt`, exactly these edits and nothing else:
1. Class KDoc becomes:
   ```kotlin
   /**
    * Baseline for a stateless JSON API: no server sessions, no cookies, deny by default, strict response headers.
    * Anonymous-session bearer tokens authenticate as `ANON_SESSION` (AnonSessionConfiguration, docs/security/ANON_SESSIONS.md);
    * account tokens (OAuth2 resource server) follow in Phase 4, see docs/PLAN.md section 8.3.
    */
   ```
2. The comment above `http {` becomes two lines:
   ```kotlin
           // Customizer<HttpSecurity> beans extend this chain: platform.ratelimit adds the rate limiter right after CorsFilter,
           // AnonSessionConfiguration adds anonymous-session authentication right before AnonymousAuthenticationFilter.
   ```
3. In `authorizeHttpRequests`, after the `/v1/meta` line add
   `authorize(HttpMethod.POST, "/v1/sessions/anonymous", permitAll)`, and replace `authorize(anyRequest, authenticated)` with:
   ```kotlin
                   // Anything else needs a signed-in account (Phase 4 grants ROLE_USER). Not `authenticated`: anonymous
                   // sessions authenticate too, and anyone can create one. Endpoints open to them get their own rule.
                   authorize(anyRequest, hasRole("USER"))
   ```
   Effects: no credentials → 401 as before (anonymous → entry point); a valid anonymous session on an unlisted path →
   403 (`ProblemDetailsAccessDeniedHandler`). `@WithMockUser` has `ROLE_USER`, so `ErrorResponsesIntegrationTest` is
   unchanged and stays green. Client rule that follows: a 401 on a request that carried a token means the token is
   dead (a live token gets 403, never 401).

### 8. The endpoint (`sessions/AnonSessionController.kt`)
```kotlin
/** Response of `POST /v1/sessions/anonymous`. */
data class AnonSessionResponse(
    val token: String,
    val tokenType: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "AnonSessionResponse(tokenType=$tokenType, expiresAt=$expiresAt, token=[REDACTED])"
}

@RestController
@RequestMapping("/v1/sessions")
@ConditionalOnRole(Role.API)
class AnonSessionController(private val issuer: AnonSessionIssuer) {
    @PostMapping("/anonymous")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(): AnonSessionResponse
}
```
- `create()` returns `AnonSessionResponse(issued.token, "Bearer", issued.principal.expiresAt)`. No request body is read;
  any body is ignored (the 1 MB cap still applies). A request that carries a valid token gets a new, unrelated session.
- 201 because a session is created; no `Location`, sessions are not addressable.
- JSON (camelCase, like the rest of the client contract):
  `{"token":"tba1.k1.…","tokenType":"Bearer","expiresAt":"2026-12-20T10:15:30Z"}`. Jackson 3.1.7 writes `Instant` as
  ISO-8601 by default (`DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` defaults to false, checked); whole seconds, so no
  fraction. Do not change global Jackson settings.
- `Cache-Control: no-store` comes from Spring Security's default headers; the test asserts it.
- `application.yaml`, at the end of `tastebuds.rate-limit.rules` (after the `/v1/meta` rule), add:
  ```yaml
        - method: POST
          pattern: /v1/sessions/anonymous
          cost: 10
  ```
  One IP can then create at most 12 sessions per minute with the default bucket (120 per minute).

### 9. Wide event fields
| Field | Type | Description |
|---|---|---|
| `principal.tier` | string | `anon`: the request carries a valid anonymous session token. Absent without credentials (`user`, `admin` from Phase 4) |
| `auth.failure` | string | adds `invalid_token`: 401 because the bearer token is malformed, signed with an unknown key, forged or expired |
| `auth.failure_reason` | string | Only with `invalid_token`: `malformed`, `unknown_key`, `bad_signature` or `expired` |

Never the token, the session id or a key id. Key names must not contain `token` (the redaction rule would blank
them); values may.

### 10. Docs
- `docs/observability/WIDE_EVENT_SCHEMA.md`, `### Auth` table: the `auth.failure` description becomes
  "`unauthenticated` (401 from the entry point), `access_denied` (403 from the access-denied handler) or
  `invalid_token` (401: the bearer token is malformed, signed with an unknown key, forged or expired); absent
  otherwise". Add the rows `auth.failure_reason` and `principal.tier` of §9 below it, then this line under the table:
  "Never the token, the session id or a key id." In "Reserved namespaces", replace
  `` `principal.*` (`principal.tier`: `anon`, `user`, `admin`), `` with `` `principal.*`, ``. Change nothing else.
- `SECURITY.md`, "Security design": after the rate-limiting bullet add exactly:
  ```markdown
  - Anonymous sessions (`POST /v1/sessions/anonymous`): HMAC-SHA256-signed bearer tokens with a fixed lifetime (30 days by default), verified without server state; invalid or expired tokens get one uniform 401 problem+json. A session opens only endpoints whose rule allows it; unlisted paths need a signed-in account. Format and key rotation: [docs/security/ANON_SESSIONS.md](docs/security/ANON_SESSIONS.md)
  ```
- New `docs/security/ANON_SESSIONS.md`, in the style of `FORWARDED_HEADERS.md`, about 60 lines, with these sections
  and facts (no more):
  1. *Purpose*: signed-out users (tastebuds_spec F01), Phases 1–3 (D4); a session is a token, not server state.
  2. *Client contract*: `POST /v1/sessions/anonymous` (no credentials, no body) → 201 with the §8 JSON; send
     `Authorization: Bearer <token>`; never in a URL; a token is live while now < `expiresAt`; any 401 on a request
     that carried the token means it is dead: drop it and create a new session (the old session's data is not
     reachable any more); a live token on an endpoint without an `ANON_SESSION` rule gets 403.
  3. *Format v1*: the §3 table and the verification order.
  4. *Keys*: generate with `openssl rand -base64 32`; env variables of §4 (more keys: `_1_`, `_2_`); every `api`
     replica needs the same keys, the `worker` role needs none; `ephemeral-signing-key` only for local development
     and tests.
  5. *Rotation*: add the new key at index 0 and keep the old one at index 1; deploy; after one TTL remove the old key.
     Leaked key: remove it at once; every session signed with it ends (clients create new ones).
  6. *Limits*: no revocation before Phase 4 (a stolen token works until it expires); no renewal; budgets are later
     work (docs/PLAN.md §5.6).

### Expected layout and size
Main code: `platform/AnonSessions.kt`, `platform/security/{AnonSessionProperties, AnonSessionTokens, BearerCredentials,
AnonSessionAuthentication, AnonSessionConfiguration}.kt`, `sessions/AnonSessionController.kt`, the `SecurityConfiguration`
edits: about 400 lines with imports and KDoc. Docs about 80 lines.

## Acceptance criteria
1. **Endpoint.** `POST /v1/sessions/anonymous` without credentials returns 201, `Content-Type: application/json`, and a
   body with exactly `token`, `tokenType` (`Bearer`) and `expiresAt` (ISO-8601 UTC, whole seconds, creation time +
   `ttl`). The response has a `Cache-Control` that contains `no-store` and no `Set-Cookie`. The controller exists only
   in the `api` role (and `all`).
2. **Token.** Every token matches `^tba1\.[a-z0-9]{1,16}\.[A-Za-z0-9_-]{32}\.[A-Za-z0-9_-]{43}$`, is signed with key 0,
   and carries a new random session id and the expiry, as in §3.
3. **Authentication.** A valid token in `Authorization: Bearer` authenticates the request as `AnonSessionAuthentication`
   with the single authority `ANON_SESSION`, principal `AnonSessionPrincipal(sessionId, expiresAt)` and null
   credentials; its wide event has `principal.tier=anon`. A token in the query string or a cookie is ignored.
4. **Rejection.** A malformed token, an unknown key id, a wrong signature, an expired token (now ≥ `expiresAt`), a
   malformed `Bearer` header or more than one `Authorization` header gets 401 problem+json with `title=Unauthorized`,
   `detail=The bearer token is invalid or expired.`, `instance=<path>` and `WWW-Authenticate: Bearer error="invalid_token"`,
   identical for every reason and also on `permitAll` paths. The chain stops. The wide event has
   `auth.failure=invalid_token` and the `auth.failure_reason` of §9. The signature is checked before the expiry, in
   constant time. Other schemes (`Basic …`) are ignored. `verify` never throws.
5. **Authorization.** `POST /v1/sessions/anonymous` is `permitAll`. Unlisted paths need `ROLE_USER`: no credentials →
   401 with `WWW-Authenticate: Bearer` (unchanged), a valid anonymous session → 403 problem+json. Existing tests pass
   unchanged.
6. **Position.** The authentication filter is in Spring Security's chain exactly once, before
   `AnonymousAuthenticationFilter` and after `CorsFilter`. No bean of type `AnonSessionAuthenticationFilter` exists.
   (The order relative to `RateLimitFilter` follows from both registrations and is tested in task 007.)
7. **Keys and roles.** Properties and validation as in §4. Role `api` without keys and without
   `ephemeral-signing-key` fails startup with the §3 message. Role `worker` starts without keys and has neither
   `AnonSessionTokens` nor the customizer. With `ephemeral-signing-key=true` and no keys, a random key is used and one
   WARN is logged. With several keys, key 0 signs and every key verifies.
8. **Cost.** `application.yaml` has the §8 rule: `POST /v1/sessions/anonymous` costs 10 tokens.
9. **Privacy.** The token appears only in the creation response. Tokens, secrets and session ids never appear in a
   wide event or a log line. `toString()` of `IssuedAnonSession`, `AnonSessionResponse`,
   `AnonSessionProperties.SigningKey`, `AnonSessionTokens.Key` and `BearerCredential.Present` shows no token or secret.
   Validation messages never contain a secret.
10. **Modules.** `sessions` uses only public types of the `platform` base package; `ModularityTest` passes.
11. **No committed keys.** The `test` profile and `bootTestRun` use the ephemeral key; tests generate key material at
    runtime; no Base64 key literal of 32 bytes or more is added anywhere.
12. **Docs.** WIDE_EVENT_SCHEMA.md, SECURITY.md and ANON_SESSIONS.md as in §10; nothing else changes in the first two.

## Required tests
Test names are the sentences below. Unit tests use a generated 32-byte secret and `Clock.fixed`.

**Shared test helper** (the only one; put any other helper inside the test class that uses it):
`services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/security/AnonSessionTestKeys.kt`
```kotlin
/** Key material and token minting for tests. Never a key literal: gitleaks scans the repo. */
internal object AnonSessionTestKeys {
    /** 32 bytes from `SecureRandom`. */
    fun randomSecret(): ByteArray
    /** Standard Base64, the form `tastebuds.anon-session.signing-keys[n].secret` expects. */
    fun base64(secret: ByteArray): String
    /** Tokens signed with [secret] under [id], expiring [ttl] after [now] (`Clock.fixed(now, UTC)`). */
    fun tokens(id: String, secret: ByteArray, ttl: Duration = Duration.ofDays(30), now: Instant = Instant.now()): AnonSessionTokens
}
```
Every test in this task that needs a key or a hand-made token uses it, including `AnonSessionIntegrationTest` in the
`sessions` test package (tests are not part of the Modulith verification). Task 007 reuses it without changing it.

- `AnonSessionTokensTest` (unit, `platform/security`; AC 2, 4, 7, 9):
  - `issued tokens verify and carry the session id and expiry` (format regex, length ≤ 128, `verify` returns the
    issued principal, `expiresAt` = fixed now + ttl in whole seconds)
  - `each session gets a new random id` (1 000 issues → 1 000 distinct ids)
  - `tokens expire at expiresAt` (clock 1 s before → `Valid`; at `expiresAt` → `EXPIRED`)
  - `tampered tokens are rejected` (negative: every single-character change of the payload → `BAD_SIGNATURE` or
    `MALFORMED`, never `Valid`; last MAC character changed → `BAD_SIGNATURE`; payload of another token with this MAC →
    `BAD_SIGNATURE`)
  - `the signature is checked before the expiry` (negative: an expired token with a changed payload → `BAD_SIGNATURE`)
  - `a token signed with another secret under the same key id is rejected` (negative: `BAD_SIGNATURE`)
  - `tokens with an unknown key id are rejected` (negative: `UNKNOWN_KEY`)
  - `older keys still verify after rotation and new tokens use the first key` (token from `[k1]` verifies with
    `[k2, k1]`; new tokens start with `tba1.k2.`)
  - `malformed tokens are rejected` (negative: `""`, `tba1`, `tba1...`, `tba2.` + a valid rest, a JWT-like
    `eyJhbGciOiJub25lIn0.e30.`, upper-case kid, `+` `/` `=` in the payload, payload of 31 and 33 characters, MAC of 42
    characters, leading or trailing space or line break, full-width digits, 129 characters, 10 000 characters →
    `MALFORMED`)
  - `random strings never verify and never throw` (10 000 strings from `Random(42)`, lengths 0..140, characters from
    `a-z A-Z 0-9 . _ - + / =` plus space and `é`, and 1 000 strings of the form `tba1.k1.<32 random>.<43 random>` →
    all `Invalid`)
  - `missing keys fail unless the ephemeral key is enabled` (`fromProperties`: no keys → `IllegalStateException` whose
    message starts with `tastebuds.anon-session.signing-keys`; ephemeral → issues verifiable tokens; two ephemeral
    instances reject each other's tokens with `BAD_SIGNATURE`)
  - `toString never shows tokens or secrets` (`IssuedAnonSession`, `AnonSessionTokens.Key`, `BearerCredential.Present`)
- `AnonSessionPropertiesTest` (unit; AC 7, 9): `defaults are valid` (30 d, no keys, ephemeral off);
  `invalid values are rejected` (one `assertFailsWith<IllegalArgumentException>` per row of the §4 table: ttl 59 min
  and 90 d + 1 s; ids `""`, 17 characters, `K1`, `k.1`; duplicate ids; secret `not base64!`; a secret of 31 bytes;
  each message starts with the property name and does not contain the secret); `toString never shows the secret`.
- `BearerCredentialsTest` (unit, `MockHttpServletRequest`; AC 3, 4): `no header means no credential`;
  `other schemes are ignored` (`Basic dXNlcjpwYXNz`); `bearer credentials are read case-insensitively`
  (`Bearer abc`, `bearer abc`, `BEARER  abc` → `abc`); `malformed bearer headers are rejected` (negative: `Bearer`,
  `Bearer `, `Bearer a b`, `Bearer a,b`, `Bearerabc`, two `Authorization` headers → `Malformed`).
- `AnonSessionAuthenticationFilterTest` (unit: `MockHttpServletRequest`/`MockHttpServletResponse`, a `FilterChain` lambda
  that captures `SecurityContextHolder.getContext().authentication`, `WideEvent.within`; clear the context after each
  test; AC 3, 4, 9):
  - `a valid token authenticates the request as an anonymous session` (captured authentication: type, authority,
    principal, null credentials; `principal.tier=anon`)
  - `requests without credentials continue unauthenticated` (chain called, no authentication, no `auth.*` field)
  - `invalid tokens stop the request with a uniform 401 problem` (negative: malformed, unknown key, bad signature,
    expired: chain not called; status, header, body equal for all four; `auth.failure_reason` per case; neither body
    nor wide event contains the token)
- `AnonSessionConfigurationTest` (`ApplicationContextRunner`, user configuration `AnonSessionConfiguration`; AC 7, 9):
  `the api role gets session tokens and the authentication customizer` (generated key via
  `tastebuds.anon-session.signing-keys[0].*`; beans `anonSessionTokens`, `anonSessionSecurityCustomizer` and a bean of
  type `AnonSessionIssuer`); `the worker role starts without a key and has no session beans`;
  `the api role without a key fails startup` (message contains `tastebuds.anon-session.signing-keys`);
  `the ephemeral key lets the api role start without configured keys`;
  `invalid settings fail startup without showing the secret` (secret `c2hvcnQ=`: the failure messages contain
  `signing-keys[0].secret` and not `c2hvcnQ=`).
- `AnonSessionIntegrationTest` (`sessions` test package; `@SpringBootTest(properties = ["tastebuds.cors.allowed-origins=https://app.example"])`,
  `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@Import(TestcontainersConfiguration::class)`,
  `@ExtendWith(OutputCaptureExtension::class)`; keys from a `@DynamicPropertySource` in the companion object: id `it`
  and a secret from `AnonSessionTestKeys.randomSecret()` generated at class load, so the test can mint its own
  tokens with `AnonSessionTestKeys.tokens("it", secret, …)`; wide events parsed as in `RateLimitIntegrationTest`):
  - `creating a session returns a bearer token and its expiry` (AC 1, 2: status, content type, exactly three fields,
    token regex with kid `it`, `expiresAt` format `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` and within 1 min of now +
    30 d, `no-store`, no `Set-Cookie`)
  - `the token authenticates later requests as an anonymous session` (`GET /v1/meta` with the token → 200, wide event
    `principal.tier=anon`) (AC 3)
  - `an anonymous session opens no endpoint without its own rule` (negative: `GET /v1/anything` with the token → 403
    problem; without a token → 401 with `WWW-Authenticate: Bearer`) (AC 5)
  - `invalid, forged and expired tokens get one uniform 401 problem` (negative, all on the public `GET /v1/meta`:
    `garbage`, a real token with its last character changed, a token from another secret under id `it`, a token with
    kid `other`, a token minted with a clock 2 h in the past and ttl 1 h, `Authorization: Bearer` with nothing after
    it → 401, `invalid_token` challenge, identical bodies, wide events with the expected `auth.failure_reason`) (AC 4)
  - `tokens are read only from the Authorization header` (negative: `GET /v1/anything?access_token=<valid>` and
    `Cookie: token=<valid>` → 401 with the plain `Bearer` challenge) (AC 3)
  - `a browser on an allowed origin can create a session` (preflight `OPTIONS /v1/sessions/anonymous` with
    `Origin: https://app.example`, `Access-Control-Request-Method: POST`, `Access-Control-Request-Headers: authorization`
    → 200; the `POST` with that `Origin` → 201 with `Access-Control-Allow-Origin: https://app.example`)
  - `tokens and session ids never appear in logs` (after creating and using a session: the captured output contains
    neither the token nor the session id from `verify`) (AC 9)
  - `the anonymous security chain has the filter once, before anonymous authentication` (from
    `FilterChainProxy.filterChains`: exactly one `AnonSessionAuthenticationFilter`, index after `CorsFilter`, before
    `AnonymousAuthenticationFilter`; `getBeanNamesForType(AnonSessionAuthenticationFilter::class.java)` empty) (AC 6)
  - `session creation has its own rate-limit cost` (`@Autowired RateLimitProperties`: rules contain
    `Rule("POST", "/v1/sessions/anonymous", 10)`) (AC 8)

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- Kover line coverage of the new classes (`platform/AnonSessions.kt`, `platform.security.AnonSession*`,
  `BearerCredentials`, `sessions`) ≥ **80%** (global floor: 70%).
- Wide event fields of §9 are documented in docs/observability/WIDE_EVENT_SCHEMA.md.
- No new dependency; no lockfile changes.
- `scripts/agentic/scope.sh diff docs/tasks/006-anon-session-tokens.md main` reports no violation.

## Notes for later tasks (not part of this task)
- Helm chart task: an `api`-only Secret with `TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_ID` / `_SECRET`; same keys on every
  `api` replica; none for `worker`.
- tastebuds_spec F01 §6 should record the contract of §8 and ANON_SESSIONS.md §2 (response shape, `Authorization:
  Bearer`, liveness by `expiresAt`, 401 → drop and re-create). No conflict with docs/PLAN.md was found.
- Phase 2 endpoints for anonymous sessions add rules such as
  `authorize("/v1/me/takes/**", hasAnyAuthority(ANON_SESSION_AUTHORITY, "ROLE_USER"))`.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
