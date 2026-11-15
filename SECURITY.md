# Security policy

## Reporting a vulnerability
Please **do not open a public issue**. Use GitHub's private vulnerability reporting
(Security tab → "Report a vulnerability"). You can expect an acknowledgement within 72 hours and a fix or
mitigation plan within 14 days for high-severity issues. Good-faith research is welcome; please avoid privacy
violations, data destruction and service degradation, and only test against your own local deployment.

## Scope
This repository (API, identity service, deployment manifests). Third-party services are out of scope.

## Security design
The control set targets OWASP ASVS 4.0 Level 2 and is described in [docs/PLAN.md §8](docs/PLAN.md#8-security).
Highlights already in place:
- Deny-by-default authorization, stateless API (no cookies, no CSRF surface), strict CORS allow-list
- Hardened headers (CSP `default-src 'none'`, HSTS preload, frame deny, no-referrer, nosniff)
- RFC 9457 errors that never expose internals; stack traces never leave the server
- Raw JDBC statements banned by architecture tests; parameterized queries only
- Actuator on a separate, non-public port with only health/info exposed
- Postgres statement timeout; request header/body size limits
- Distroless non-root container image; Trivy, CodeQL, gitleaks and Renovate in CI
- Telemetry redaction of sensitive fields at the source
