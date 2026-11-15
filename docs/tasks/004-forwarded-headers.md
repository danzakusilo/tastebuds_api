# 004 — Trust forwarded headers only from internal proxies

- **Status:** READY
- **Phase:** 0 (hardening from the 2026-11-15 external review, item 9)
- **Planned by:** claude-opus-5-5 on 2026-11-15
- **Depends on:** 003 (both edit SECURITY.md)

## Decision (user, 2026-11-15)
`server.forward-headers-strategy: native` (Tomcat `RemoteIpValve` with `server.tomcat.remoteip.internal-proxies`),
not `framework`. Reason: `framework` installs Spring's `ForwardedHeaderFilter`, which honours `Forwarded` /
`X-Forwarded-*` from **any** peer and has no trusted-proxy setting, so any pod reaching port 8080 could spoof its client
IP (rate-limit evasion in task 001) and scheme. `native` honours the headers only when the immediate peer matches
`internal-proxies` and resolves the client address by walking `X-Forwarded-For` from the right past trusted proxies
only, so a client-supplied prefix cannot choose the address.

## Goal
One app-wide decision for running behind a TLS-terminating ingress (docs/PLAN.md §3, §8.1, D16): `request.isSecure`,
scheme and `request.remoteAddr` reflect the real client when, and only when, the request came through a trusted proxy.
This makes HSTS (written only for secure requests) correct in production and gives task 001 a spoof-resistant client
address. Documented in `docs/security/FORWARDED_HEADERS.md`.

## Non-goals
- Cloudflare-specific trust (`trusted-proxies` with Cloudflare ranges / `CF-Connecting-IP`): Phase 6; documented as a TODO only.
- `X-Forwarded-Prefix` / `Forwarded` (RFC 7239) support, ingress or Helm configuration.
- Changing task 001 (it will be revised separately to use `request.remoteAddr` and drop its own XFF parsing).
- Adding a client address field to the wide event (IP addresses are personal data).

## Allowed paths
- `services/core/src/main/resources/application.yaml`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/web/ForwardedHeadersIntegrationTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/web/UntrustedProxyIntegrationTest.kt`
- `services/core/src/test/kotlin/dev/danya/tastebuds/core/platform/web/RequestInfoCapture.kt`
- `docs/security/FORWARDED_HEADERS.md`
- `SECURITY.md`

## Design notes
### Config (application.yaml, under `server:`)
```yaml
  # Behind a TLS-terminating ingress. X-Forwarded-For/-Proto are honoured only from internal proxies, see
  # docs/security/FORWARDED_HEADERS.md. Narrow the trusted range per environment with
  # SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES (a Java regex over the peer IP).
  forward-headers-strategy: native
  tomcat:
    # (merge into the existing `tomcat:` block, keep its keys)
    remoteip:
      remote-ip-header: x-forwarded-for
      protocol-header: x-forwarded-proto
```
Do **not** set `internal-proxies`: Boot's default (loopback, 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10,
IPv6 loopback/link-local/ULA) is the right default for an in-cluster ingress; environments narrow it via the env var.

### Test helper `RequestInfoCapture.kt` (test sources)
A `@TestConfiguration(proxyBeanMethods = false)` class `RequestInfoCapture` exposing a `Filter` bean
(`OncePerRequestFilter`, registered at `Ordered.HIGHEST_PRECEDENCE`) that records, for the last request,
`remoteAddr`, `scheme`, `isSecure` into a `@Volatile` field of a `data class Captured(val remoteAddr: String, val scheme: String, val secure: Boolean)`
held by the bean (`fun last(): Captured`). Imported explicitly by the two tests (`@TestConfiguration` is excluded from
component scanning, so other tests are unaffected).

### Tests (real HTTP, `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@LocalServerPort`, `java.net.http.HttpClient`)
The valve runs inside Tomcat, so MockMvc cannot observe it. Requests go to `GET /v1/meta` from loopback.

### `docs/security/FORWARDED_HEADERS.md`
Sections: **Decision** (`native`, why not `framework`, one paragraph each); **What is trusted** (internal-proxies
default, env override, right-to-left XFF walk); **Operator checklist** (ingress must set `X-Forwarded-Proto` and
append the real peer to `X-Forwarded-For`; narrow `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to the ingress pod CIDR
once NetworkPolicies exist); **Consumers** (rate limiting and any per-client logic use `request.remoteAddr` only and
never parse `X-Forwarded-For` themselves; HSTS depends on `isSecure`); **Phase 6 TODO** (Cloudflare: add its ranges to
`server.tomcat.remoteip.trusted-proxies` or have the ingress restore the client IP from `CF-Connecting-IP`).

### SECURITY.md
Add one bullet to "Security design" after the hardened-headers bullet:
`Forwarded headers honoured only from internal proxies (client IP and scheme behind the ingress), see [docs/security/FORWARDED_HEADERS.md](docs/security/FORWARDED_HEADERS.md)`.

## Acceptance criteria
1. From a trusted peer, `X-Forwarded-For: 203.0.113.7` + `X-Forwarded-Proto: https` → `remoteAddr = 203.0.113.7`,
   `isSecure = true`, scheme `https`, and the response carries `Strict-Transport-Security`.
2. Without forwarded headers → `remoteAddr` is the loopback address, `isSecure = false`, no HSTS header.
3. A client-supplied prefix cannot choose the address: `X-Forwarded-For: 198.51.100.9, 203.0.113.7` → `remoteAddr = 203.0.113.7`.
4. From an untrusted peer (internal-proxies configured not to match loopback), the same forwarded headers are ignored:
   loopback `remoteAddr`, not secure, no HSTS.
5. `docs/security/FORWARDED_HEADERS.md` exists with the sections above; SECURITY.md has the new bullet.

## Required tests
- `ForwardedHeadersIntegrationTest` (`@ActiveProfiles("test")`, `@Import(TestcontainersConfiguration::class, RequestInfoCapture::class)`):
  - `forwarded client address and scheme are honoured from a trusted proxy` (AC1)
  - `direct requests keep the peer address and are not secure` (AC2)
  - `a client-supplied X-Forwarded-For prefix cannot choose the address` (AC3, negative)
- `UntrustedProxyIntegrationTest` (same setup plus
  `properties = ["server.tomcat.remoteip.internal-proxies=10\\.255\\.255\\.255"]`):
  - `forwarded headers from an untrusted peer are ignored` (AC4, negative: spoofing attempt)

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- Kover: global floor 70% (this task adds configuration only).
- No new dependency.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
