# 003 — problem+json for security errors, request body cap, truthful security docs

- **Status:** READY
- **Phase:** 0 (hardening from the 2026-11-15 external review, items 5 and 10)
- **Planned by:** claude-opus-5-5 on 2026-11-15
- **Depends on:** none (independent of 002; 004 depends on this one because both edit SECURITY.md)

## Goal
Make the error contract and the security documentation true (docs/PLAN.md §6 conventions, §8.2, §8.4):
- `ProblemDetailsAdvice`'s catch-all `@ExceptionHandler(Exception::class)` turns `AccessDeniedException` /
  `AuthenticationException` thrown inside MVC (e.g. future method security) into `500`. They must reach Spring
  Security's `ExceptionTranslationFilter`, and both the 401 entry point and the 403 access-denied handler must emit
  RFC 9457 problem+json.
- SECURITY.md claims "body size limits", but JSON bodies are unbounded. Add an application-level request body cap
  (413 problem+json) so the claim is true.
- Correct the other overclaims in SECURITY.md (parameterized queries, "every error", ASVS version per D15) and add the
  Apache-2.0 `LICENSE` file (user decision).

## Non-goals
- Bearer-token authentication / OAuth2 resource server, method security (`@EnableMethodSecurity`), per-route body limits.
- Making CORS rejections or container-level errors (oversized headers, Tomcat 400s rendered via `/error`) problem+json:
  recorded in `docs/backlog/2026-11-15-non-problem-json-error-paths.md`, documented as known gaps in SECURITY.md.
- Forwarded headers / trusted proxies (task 004), rate limiting (task 001).
- The ArchUnit string-SQL gap itself (`docs/backlog/2026-11-15-archunit-sql-string-templates.md`); only document it.
- Any edit to docs/PLAN.md, CLAUDE.md, or README sections other than the one named below.

## Allowed paths
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/web/**`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/security/**`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/TastebudsProperties.kt`
- `services/core/src/main/resources/application.yaml`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/web/**`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/security/**`
- `docs/observability/WIDE_EVENT_SCHEMA.md`
- `SECURITY.md`
- `README.md`
- `LICENSE`

## Design notes

### 1. Shared problem+json writer (servlet level, before MVC)
`platform/web/ProblemResponses.kt`:
```kotlin
internal object ProblemResponses {
    /** Writes an RFC 9457 body for responses produced outside Spring MVC (filters, Spring Security handlers). */
    fun write(request: HttpServletRequest, response: HttpServletResponse, status: HttpStatus, title: String, detail: String)
}
```
- Sets `response.status`, `Content-Type: application/problem+json` (`MediaType.APPLICATION_PROBLEM_JSON_VALUE`),
  writes `{"type":"about:blank","title":…,"status":…,"detail":…,"instance":<request.requestURI>}` (a `LinkedHashMap`
  serialized with a private `JsonMapper.builder().build()` from `tools.jackson.databind.json`). Same field set as
  Spring's `ProblemDetail`, so clients see one shape. Does nothing if `response.isCommitted`.

### 2. Spring Security handlers
`platform/security/ProblemDetailsSecurityHandlers.kt` with two classes (both `internal`):
- `ProblemDetailsAuthenticationEntryPoint : AuthenticationEntryPoint` → 401, title `Unauthorized`, detail
  `Authentication is required.`, header `WWW-Authenticate: Bearer`, wide event `auth.failure = "unauthenticated"`.
- `ProblemDetailsAccessDeniedHandler : AccessDeniedHandler` → 403, title `Forbidden`, detail `Access is denied.`,
  wide event `auth.failure = "access_denied"`.
- Never include the exception message in the body or the wide event.
- `SecurityConfiguration`: in `exceptionHandling { }` set `authenticationEntryPoint = ProblemDetailsAuthenticationEntryPoint()`
  (replaces `HttpStatusEntryPoint`) and `accessDeniedHandler = ProblemDetailsAccessDeniedHandler()`. No other changes
  to the filter chain (authorization rules, headers, CORS untouched).

### 3. ProblemDetailsAdvice
- `handleUnexpected`: first statement rethrows Spring Security exceptions so `ExceptionTranslationFilter` handles them:
  `if (error is AccessDeniedException || error is AuthenticationException) throw error`. Spring MVC's
  `ExceptionHandlerExceptionResolver` treats a rethrow of the *same* exception as "not handled" and lets it propagate
  out of the `DispatcherServlet`. Comment this in one line. Nothing is logged and the wide event is not touched for them.
- Body-limit mapping (see 4): private helper `bodyTooLarge(ex: Throwable): RequestBodyTooLargeException?` walking the
  cause chain. Used in two places:
  - `handleUnexpected`: if found, return the 413 problem instead of 500 (no error log; `WideEvent.current()?.put("http.request.body_too_large", true)`).
  - Override `handleHttpMessageNotReadable(ex, headers, status, request)`: if found, return
    `handleExceptionInternal(ex, problem413, headers, HttpStatus.CONTENT_TOO_LARGE, request)` and set the same wide
    event field; otherwise `super`.
  - 413 problem: status `HttpStatus.CONTENT_TOO_LARGE` (not `PAYLOAD_TOO_LARGE`, which is deprecated and would fail
    the build under warnings-as-errors), title `Content Too Large`, detail `Request body exceeds <n> bytes.` with
    `n` = the exception's `limitBytes`.

### 4. Request body cap
Config (in `TastebudsProperties`, new nested class; YAML in application.yaml under `tastebuds:`):
```yaml
  web:
    max-request-body-size: 1MB   # applies to every request body; bigger uploads get their own route-level limit later
```
```kotlin
data class Web(
    /** Upper bound for any request body; larger requests get 413. */
    val maxRequestBodySize: DataSize = DataSize.ofMegabytes(1),
)
```
(`org.springframework.util.unit.DataSize`; add `val web: Web = Web()` to `TastebudsProperties`.)

Types in `platform/web`:
- `class RequestBodyTooLargeException(val limitBytes: Long) : RuntimeException("Request body exceeds $limitBytes bytes")`
- `internal class RequestBodyLimitFilter(private val maxBytes: Long) : OncePerRequestFilter()`:
  - If `request.contentLengthLong > maxBytes`: `WideEvent.current()?.put("http.request.body_too_large", true)`, write
    the 413 via `ProblemResponses.write` (same title/detail as above), do not call the chain.
  - Otherwise call the chain with a `HttpServletRequestWrapper` whose `getInputStream()` returns a counting
    `ServletInputStream` (delegating `isFinished/isReady/setReadListener`) that throws
    `RequestBodyTooLargeException(maxBytes)` as soon as more than `maxBytes` bytes have been read (covers chunked /
    unknown-length bodies), and whose `getReader()` is a `BufferedReader(InputStreamReader(getInputStream(), charset))`
    with the request's character encoding (UTF-8 if null). Wrap once per request (cache the stream).
- `internal class RequestBodyLimitConfiguration` (`@Configuration(proxyBeanMethods = false)`) registering
  `FilterRegistrationBean(RequestBodyLimitFilter(properties.web.maxRequestBodySize.toBytes()))` with
  `order = Ordered.HIGHEST_PRECEDENCE + 3` (directly after `WideEventFilter` at +2 so the rejection has a wide event,
  before Spring Security so oversized anonymous requests are rejected cheaply). All roles (no `@ConditionalOnRole`).
- Keep the existing Tomcat settings (`max-swallow-size`, `max-http-form-post-size`) as they are.

### 5. Wide event fields (document in `WIDE_EVENT_SCHEMA.md`)
- HTTP table: `http.request.body_too_large` | boolean | `true` when the request was rejected with 413 (absent otherwise).
- New section `### Auth` (or a row under reserved `auth.*`, removing nothing): `auth.failure` | string |
  `unauthenticated` (401 from the entry point) or `access_denied` (403 from the access-denied handler).

### 6. Docs
- `SECURITY.md`, "Security design" section. Replace the bullets as follows (keep the others verbatim):
  - Line 13: `OWASP ASVS 4.0 Level 2` → `OWASP ASVS 5.0 Level 2` (D15).
  - `RFC 9457 errors that never expose internals; stack traces never leave the server` →
    `RFC 9457 problem+json for application errors, including 401/403 from Spring Security and 413 for oversized bodies; internals and stack traces never leave the server. Known gaps: CORS rejections and errors raised by the servlet container before the application (e.g. oversized headers) use the default body`.
  - `Raw JDBC statements banned by architecture tests; parameterized queries only` →
    `Raw `java.sql.Statement` use is banned by an ArchUnit test; queries use jOOQ or bound parameters by convention and review. Known gap: string-built SQL passed to `JdbcClient`/jOOQ plain-SQL APIs is not yet detected automatically (planned Semgrep rule)`.
  - `Postgres statement timeout; request header/body size limits` →
    `Postgres statement timeout (5 s); request limits: headers 16 KB, any request body 1 MB (413 problem+json), form posts 1 MB`.
- `README.md`: append a `## License` section at the end: `Apache License 2.0, see [LICENSE](LICENSE).`
  Touch nothing else in README (it may contain unrelated uncommitted edits from the main session; leave them).
- `LICENSE`: the canonical, unmodified text of https://www.apache.org/licenses/LICENSE-2.0.txt (including the
  APPENDIX with its `[yyyy] [name of copyright owner]` placeholders, as GitHub's license detection expects).

## Acceptance criteria
1. An unauthenticated request to a protected path (`GET /v1/anything`) returns 401 with `Content-Type:
   application/problem+json`, `WWW-Authenticate: Bearer`, `status: 401`, `title: Unauthorized`, `instance` = path.
2. An `AccessDeniedException` thrown by a controller for an authenticated user yields 403 problem+json (not 500),
   produced by the access-denied handler.
3. An `AuthenticationException` thrown by a controller yields 401 problem+json (not 500).
4. Any other unexpected exception still yields the existing generic 500 problem with `traceId` and no message leak.
5. A request whose `Content-Length` exceeds 1 MB is rejected with 413 problem+json before authentication (also for
   anonymous requests to protected paths), and its wide event has `http.request.body_too_large = true`.
6. A body without a usable `Content-Length` that streams past the limit fails with `RequestBodyTooLargeException`,
   which the advice maps to 413 whether it arrives raw or wrapped in `HttpMessageNotReadableException`.
7. Bodies at or below the limit are passed through unchanged.
8. Wide event fields `auth.failure` and `http.request.body_too_large` are documented; SECURITY.md, README.md and
   LICENSE match the Design notes exactly.

## Required tests
- `ErrorResponsesIntegrationTest` (new, `.../core/platform/web/`; `@SpringBootTest`, `@AutoConfigureMockMvc`,
  `@ActiveProfiles("test")`, `@Import(TestcontainersConfiguration::class, ErrorResponsesIntegrationTest.ThrowingController::class)`).
  `ThrowingController` is a `@RestController` **nested inside the test class** (nested test classes are excluded from
  component scanning, so other tests never see it) with `GET /v1/test/access-denied` (throws
  `AccessDeniedException("x")`), `GET /v1/test/authentication-failure` (throws `BadCredentialsException("x")`),
  `GET /v1/test/boom` (throws `IllegalStateException("secret detail")`). Tests:
  - `unauthenticated requests get a 401 problem with a bearer challenge` (AC1, negative)
  - `access denied inside a controller becomes a 403 problem, not a 500` (`@WithMockUser`) (AC2, negative)
  - `authentication failures inside a controller become a 401 problem, not a 500` (`@WithMockUser`) (AC3)
  - `unexpected errors stay a generic 500 problem without the message` (`@WithMockUser`; body does not contain
    `secret detail`) (AC4)
  - `oversized bodies are rejected with 413 before authentication` (anonymous `POST /v1/meta` with a 1 MB + 1 byte
    body → 413 problem+json) (AC5, negative)
- `ProblemDetailsSecurityHandlersTest` (new, unit, `.../core/platform/security/`): entry point and handler write
  status, content type, body fields, `WWW-Authenticate` (entry point only) and set `auth.failure` on the current
  wide event (use `WideEvent.within`); the exception message never appears in the body.
- `RequestBodyLimitFilterTest` (new, unit, `MockHttpServletRequest`/`MockFilterChain`): Content-Length over the limit
  → 413 problem body, chain not invoked, wide event field set; unknown length (wrap the mock request in an
  `HttpServletRequestWrapper` returning `-1` from `getContentLengthLong`/`getContentLength`) streaming `limit + 1`
  bytes → `RequestBodyTooLargeException`; exactly `limit` bytes via stream and via reader → read fully, no exception.
- `ProblemDetailsAdviceTest` (extend; keep the existing test): `security exceptions are rethrown for Spring Security`
  (both types, `assertFailsWith` same instance); `raw body-limit exception maps to 413`;
  `body-limit exception wrapped in HttpMessageNotReadableException maps to 413` (call through
  `handleException(ex, ServletWebRequest(MockHttpServletRequest()))`).

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- Kover: new/changed code line coverage ≥ **80%** (global floor: 70%).
- Wide event fields added for new behaviour are documented in docs/observability/WIDE_EVENT_SCHEMA.md.
- No new dependency (spring-security-test and spring-test are already test dependencies).

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
