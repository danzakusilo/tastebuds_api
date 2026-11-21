# RemoteIpValve resolves host names from X-Forwarded-For when internal-proxies is a CIDR list
- **Found during:** spec audit 2026-11-21 (task 004; checklist rule 7)
- **Location:** services/core/src/main/resources/application.yaml:48-58 (Boot's default `internal-proxies`, a CIDR
  list); docs/security/FORWARDED_HEADERS.md:21-27 (calls CIDR "preferred")
- **Severity:** security
- **What:** With a CIDR value, Tomcat's valve checks each `X-Forwarded-For` entry it walks with
  `NetMaskSet.contains(String)`, which calls `InetAddress.getByName`: a host name causes a DNS lookup on the request
  thread, before the rate limiter runs. A name that resolves into a trusted range counts as a proxy, and the walk
  continues, so one 16 KB header can cause many lookups. A regex value does not resolve names. Source:
  tomcat-embed-core 11.0.27 `RemoteIpValve.java:617, :769-774, :791-801`, `NetMaskSet.java:75-78`; the audit confirmed
  `NetMaskSet.parse(<Boot default>).contains("localhost")` resolves the name. Only peers in the trusted range can do
  this: every pod until the range is narrowed, or external clients if the ingress forwards their `X-Forwarded-For`.
  Task 013 documents the side effect; this item is the mitigation.
- **Why it matters:** A client-controlled header makes the api pod do network I/O with names the client chooses.
- **Suggested fix:** Decide in the ingress task: a regex `internal-proxies` value, or a custom valve that ignores
  entries that are not IP literals; restrict port 8080 with a NetworkPolicy. Related:
  2026-11-18-mesh-sidecar-internal-proxies.md.
