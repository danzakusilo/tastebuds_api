# Security policy

## Reporting a vulnerability
Please **do not open a public issue**. Use GitHub's private vulnerability reporting
(Security tab → "Report a vulnerability"). You can expect an acknowledgement within 72 hours and a fix or
mitigation plan within 14 days for high-severity issues. Good-faith research is welcome; please avoid privacy
violations, data destruction and service degradation, and only test against your own local deployment.

## Scope
This repository (API, identity service, deployment manifests). Third-party services are out of scope.

## Security design
The control set targets OWASP ASVS 5.0 Level 2 and is described in [docs/PLAN.md §8](docs/PLAN.md#8-security).
Highlights already in place:
- Deny-by-default authorization, stateless API (no cookies, no CSRF surface), strict CORS allow-list
- Hardened headers (CSP `default-src 'none'`, HSTS preload, frame deny, no-referrer, nosniff)
- Forwarded headers honoured only from internal proxies (client IP and scheme behind the ingress), see [docs/security/FORWARDED_HEADERS.md](docs/security/FORWARDED_HEADERS.md)
- RFC 9457 problem+json for application errors, including 401/403 from Spring Security and 413 for oversized bodies; internals and stack traces never leave the server. Known gaps: CORS rejections; responses that Spring Boot's `/error` endpoint renders as its default JSON, for example the 400 (without `X-Content-Type-Options`) for requests the Spring Security firewall rejects, such as a `;`, `//` or an encoded `.` in the path; and errors raised by the servlet container before the application, such as oversized headers or an encoded `/` in the path (Tomcat's HTML error page)
- Raw `java.sql.Statement` use is banned by an ArchUnit test; queries use jOOQ or bound parameters by convention and review. Known gap: string-built SQL passed to `JdbcClient`/jOOQ plain-SQL APIs is not yet detected automatically (planned Semgrep rule)
- Least-privilege database roles: the app connects as a member of `app_rw` (data access in schema `core` only: no
  DDL, no access to the migration history); migrations run as the non-superuser schema owner `migrator` in a separate
  `migrate` process, and integration tests use the same roles. See [docs/deploy/DATABASE.md](docs/deploy/DATABASE.md)
- Actuator on a separate, non-public port with only health/info exposed
- Postgres statement timeout (5 s); request limits: headers 16 KB, any request body 1 MB (413 problem+json), form posts 1 MB
- Per-client rate limiting on `/v1/**`: cost-weighted token buckets in Redis, keyed by client IP (IPv6 by /64) and, for requests with a valid anonymous session token, also by session (both buckets must allow), checked before authentication; over the limit: 429 problem+json with `Retry-After`. Fails open while Redis is unavailable
- Anonymous sessions (`POST /v1/sessions/anonymous`): HMAC-SHA256-signed bearer tokens with a fixed lifetime (30 days by default), verified without server state; invalid or expired tokens get one uniform 401 problem+json. A session opens only endpoints whose rule allows it; unlisted paths need a signed-in account. Format and key rotation: [docs/security/ANON_SESSIONS.md](docs/security/ANON_SESSIONS.md)
- Distroless non-root container image; Trivy, CodeQL, gitleaks and Renovate in CI
- Helm chart: every pod runs with the restricted Pod Security profile (non-root, read-only root filesystem, all
  capabilities dropped, `RuntimeDefault` seccomp, no service-account token, no service links); only the migration Job
  receives the schema owner's credentials, see [deploy/helm/tastebuds-core/README.md](deploy/helm/tastebuds-core/README.md)
- Telemetry redaction of sensitive fields at the source
