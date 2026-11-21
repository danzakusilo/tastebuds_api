# 013 — Fix the documentation findings of the spec audit (key rotation, forwarded headers, database roles, error gaps)

- **Status:** HANDOFF
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-21; amended after review round 1 on 2026-11-21 (A2, D4, D5, B1 changed;
  Y2, M1 added; see "Round 2" in the design notes)
- **Depends on:** none

## Goal
The spec audit of 2026-11-21 applied docs/agentic/SPEC_CHECKLIST.md to tasks 001–011 and found eleven places (C1–C11)
where operator and security docs are wrong, incomplete or use undefined notation. Two of them can hurt in production:
the leaked-key procedure in docs/security/ANON_SESSIONS.md stalls the deploy and keeps the leaked key in service (C1),
and docs/security/FORWARDED_HEADERS.md does not say that Tomcat resolves host names from `X-Forwarded-For` (C5). This
task corrects the docs (PLAN.md §8 security, §8.1 ingress, §8.4 database roles, D4 anonymous sessions), the KDoc of
`ProblemDetailsAdvice` and one comment in `application.yaml`, and corrects the backlog item about non-problem+json
error paths. Every technical claim in the new text was checked by running the tool or by reading the code (see
"Assumptions verified"). No behaviour changes.

## Non-goals
- Any behaviour or configuration change: no Kotlin code except the KDoc, no YAML value, no new or changed test.
- `services/core/src/main/resources/db/migration/V2__app_rw_grants.sql`: immutable (Flyway checksum). Its comment
  ("Flyway runs as the schema owner, so …") stays, even though the new DATABASE.md section gives the full rule.
- A test that a table created after V2 is usable by the app (task 014, audit D2).
- Mitigating the `RemoteIpValve` DNS lookups (regex `internal-proxies` or a custom valve; backlog, audit F6),
  NetworkPolicies, or narrowing `internal-proxies` in any environment.
- Changing the startup error message for a secret with a trailing newline (`AnonSessionProperties`).
- Making firewall, `/error` or container responses problem+json. The backlog item stays open; this task only corrects
  its description.
- `docs/backlog/2026-11-20-anon-sessions-doc-expiry-range.md` (another clause for ANON_SESSIONS.md "Verification
  order"): separate item, not part of the audit findings.
- Who runs `ALTER EXTENSION vector UPDATE` in the k3d cluster (task 010, audit part A).
- Old task specs (for example 004's "ingress pod CIDR" wording), the Helm chart README, `CLAUDE.md`, and the rate
  limiting bullet in `SECURITY.md` (C11 is fixed with the `application.yaml` comment only).
- A link from the chart README's "Rotating a Secret" to the new "Leaked key" section (`deploy/` is outside the allowed
  paths; backlog item `docs/backlog/2026-11-21-chart-readme-leaked-key-link.md`).

## Allowed paths
- `docs/security/ANON_SESSIONS.md`
- `docs/security/FORWARDED_HEADERS.md`
- `docs/deploy/DATABASE.md`
- `docs/observability/WIDE_EVENT_SCHEMA.md`
- `SECURITY.md`
- `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/web/ProblemDetailsAdvice.kt`
- `services/core/src/main/resources/application.yaml`
- `docs/backlog/2026-11-15-non-problem-json-error-paths.md`
- `docs/backlog/2026-11-18-mesh-sidecar-internal-proxies.md`

## Design notes

### How to apply
- Below are 17 edits. Each **Current** block occurs exactly once in its file on `origin/main` (68a07fa). Replace it
  with the **New** block. The blocks do not overlap, so the order does not matter.
- Copy the text byte for byte from this spec: keep the continuation indents (two spaces in bullets, three in numbered
  steps, five in nested steps), add no trailing whitespace, and keep each file's final newline. Do not re-wrap.
- Change nothing else in these files. The acceptance criteria check that every changed line appears verbatim in this
  spec.
- B1 and M1 replace a whole backlog file (Current = its full content on `origin/main`). Both items stay open; do not
  delete them.
- The **New** block of D4 is fenced with four backticks because it contains a `sql` code block. Copy the lines between
  the four-backtick fences, including the three-backtick `sql` fence lines.
- K1 changes only KDoc lines. `[ProblemResponses]` resolves: `ProblemResponses` is in the same package
  (`platform.web`).

### Round 2 (after review round 1)
The round 1 implementation is in the worktree, uncommitted. The review found wording gaps; this spec now holds the
corrected text. Apply the delta:
1. Restore these files from `origin/main` and apply all of their edits again from this spec:
   `docs/security/ANON_SESSIONS.md` (A1, A2), `docs/deploy/DATABASE.md` (D1–D5),
   `docs/backlog/2026-11-15-non-problem-json-error-paths.md` (B1), `services/core/src/main/resources/application.yaml`
   (Y1, Y2). For example `git checkout origin/main -- docs/deploy/DATABASE.md`, then apply D1–D5.
2. Apply the new edit M1 to `docs/backlog/2026-11-18-mesh-sidecar-internal-proxies.md`.
3. Leave the other files (FORWARDED_HEADERS.md, WIDE_EVENT_SCHEMA.md, SECURITY.md, `ProblemDetailsAdvice.kt`) as they
   are; their edits did not change.

| Review item | Change in this spec | Edits |
|---|---|---|
| 1 | Owner change without a window: the superuser runs `REASSIGN OWNED` and both `ALTER DEFAULT PRIVILEGES FOR ROLE <new owner>` statements in one step, then the migrate credential switches; no migration. New subsection "Changing the schema owner" | D4 |
| 2 | "Never set `FLYWAY_USERNAME` to another login" now names the exception (step 2 of the owner change); the environment table no longer says "the role V2 recorded" | D4, D5 |
| 3 | Leaked key: old pods sign with the leaked key only when it is at index 0; an old pod that restarts reads the edited Secret and fails too | A2 |
| 4 | CORS 403 has no `Content-Type` header (not text/plain) | B1 |
| 5 | Sequence error message `permission denied for sequence <sequence>` | D4 |
| 6a | `application.yaml` comment above `forward-headers-strategy` gets the DNS caveat and the NetworkPolicy advice | Y2 |
| 6b | Backlog item on sidecar meshes rewritten for the new FORWARDED_HEADERS.md advice (kept open for Phase 6) | M1 |

### Finding to edit map
| Finding | Problem | Edits |
|---|---|---|
| C1 | "Remove a leaked key" leaves a gap or no key; new api pods fail at startup, the rollout stops, old pods keep the leaked key | A2 |
| C2 | Key index notation (`_1_`, "index 0") never defined | A1 |
| C3 | Rotation step 3 "after one TTL" has no start point; TTL setting not named | A2 |
| C4 | The documented generator writes a trailing newline that the decoder rejects | A1 |
| C5 | DNS lookups for host names in `X-Forwarded-For` with a CIDR list | F2 |
| C6 | "Narrow to the ingress pod CIDR" is impossible on k3s | F1, F3 |
| C7 | V2 default privileges cover only objects that the role that ran V2 creates | D1, D4, D5 |
| C8 | `migrator` cannot update the superuser-owned `vector` extension | D2 |
| C9 | `<version>` and `<release>` undefined | D3, W1 |
| C10 | SECURITY.md gap list incomplete; advice KDoc says "every error"; backlog item describes the oversized-header path wrongly and misses the firewall | S1, K1, B1 |
| C11 | Changing rate-limit capacity or refill changes the bucket keys | Y1 |
| C5, C6 (follow-up) | The `application.yaml` comment and the sidecar-mesh backlog item still advised narrowing to the ingress | Y2, M1 |

Wording decisions: in A2 the leaked-key procedure keeps the remaining keys and moves them down (a key that old pods
already know keeps their tokens valid) and adds a new key only when none remains; step 3 waits one token lifetime
plus one hour, counted from the end of the step 2 rollout (see "Limits"). In S1 the gaps are grouped by the component
that writes the response (CORS processor, Boot's `/error`, Tomcat), because that decides the fix. F1, F3, Y2 and M1
remove every promise to narrow the range "to the ingress". In D4 the owner change is done by a superuser in one SQL
step, not by a migration: a migration would need the new owner to run it, and a deploy between the reassignment and
that migration would fail at the migrate Job.

### Edits
#### A1 Keys: index rule and generator (C2, C4)
File: `docs/security/ANON_SESSIONS.md`

Current:
```text
- Generate a key with `openssl rand -base64 32`.
- Set `TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_ID` and `TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_SECRET` from a secret. More keys
  use `_1_`, `_2_`.
```

New:
```text
- Set `TASTEBUDS_ANONSESSION_SIGNINGKEYS_<n>_ID` and `TASTEBUDS_ANONSESSION_SIGNINGKEYS_<n>_SECRET` from a secret.
  `<n>` is the index of the key in the list: index 0 signs new tokens, and every key in the list verifies. The indices
  must run 0, 1, 2, ... with no gap and no leading zero (`_01_` is not index 1). A gap or a leading zero leaves
  variables unbound, and the `api` role fails at startup. It also fails at startup without any key.
- Generate a secret with `openssl rand -base64 32 | tr -d '\n'`. The value must be one line without a trailing
  newline: the Base64 decoder rejects the newline that `openssl rand -base64 32` writes, and startup fails with
  `tastebuds.anon-session.signing-keys[<n>].secret must be Base64 of at least 32 bytes`. A Secret created with
  `kubectl create secret generic --from-file` keeps the newline at the end of the file.
```

#### A2 Rotation step 3 and leaked key (C1, C3; amended after review round 1)
File: `docs/security/ANON_SESSIONS.md`

Current:
```text
3. After one TTL, remove the old key. Deploy.

A leaked key: remove it at once. Every session signed with it ends, and clients create new ones.
```

New:
```text
3. Wait until the step 2 rollout has finished on every `api` replica, then wait one token lifetime and one hour more
   (a margin for old pods that are still shutting down). Then remove the old key. Deploy.

The token lifetime is `tastebuds.anon-session.ttl` (environment variable `TASTEBUDS_ANONSESSION_TTL`): 30 days by
default, from 1 hour to 90 days. Replicas of the old release sign with the old key until the step 2 rollout ends, and
each token keeps the lifetime that was set when it was issued. If the lifetime changed while the old key signed, wait
for the longest value.

## Leaked key
Take the leaked key out of service at once, but always deploy a valid key list:

1. Write the key list again without the leaked key. Move the remaining keys down so that the indices run 0, 1, 2, ...
   with no gap. If no key remains, put a new key at index 0.
2. Deploy to every `api` replica.

Deleting only the variables of the leaked key works only when it has the highest index and another key remains. In
every other case the list has a gap or no key: the new `api` pods fail at startup and the rollout stops (the Helm
chart rolls out with `maxUnavailable: 0`). The old pods keep serving with the list they started with: they accept the
leaked key, and if it is at index 0 they also sign new tokens with it. The chart reads every key from one Secret, so
an old pod that restarts after the Secret was edited reads the broken list and fails at startup too.

Every session signed with the leaked key ends, and clients create new ones. During the rollout, old pods reject tokens
signed with a key that only the new pods know, and new pods reject tokens signed with the leaked key. A client that
gets a 401 creates a new session, so a client can lose its session more than once during the rollout.
```

#### F1 Decision: no promise to narrow to the ingress (C6)
File: `docs/security/FORWARDED_HEADERS.md`

Current:
```text
peer and has no trusted-proxy setting. With `native` the trust boundary is configurable and can be narrowed to the
ingress; see the caveat below about the default range.
```

New:
```text
peer and has no trusted-proxy setting. With `native` the trust boundary is configurable; see the caveats below about
the default range and the operator checklist.
```

#### F2 Caveat, DNS lookups, override (C5)
File: `docs/security/FORWARDED_HEADERS.md`

Current:
```text
- **Caveat:** until the range is narrowed, any peer in those ranges (in a typical cluster, every pod) is fully trusted.
  Such a peer can choose its client address (any string, not necessarily a valid IP) and its scheme.
- Override per environment with `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`: a comma-separated CIDR list (preferred, e.g.
  `10.1.0.0/16`) or, if the value contains no `/`, a single Java regex. The regex must match the whole peer IP, and IPv6
  peers appear uncompressed (`0:0:0:0:0:0:0:1`), which is why CIDR is preferred. Setting the value replaces the default
  entirely, so loopback stops being trusted unless it is listed.
```

New:
```text
- **Caveat:** any peer in the trusted ranges (with the default, every pod of a typical cluster) is fully trusted. Such
  a peer can choose its client address (any string, not necessarily a valid IP) and its scheme, and it can make the pod
  resolve host names of its choice (next item).
- **DNS lookups:** with a CIDR list (Boot's default), Tomcat passes each `X-Forwarded-For` entry it walks to
  `InetAddress.getByName`. An entry that is a host name causes a DNS lookup on the request thread, before rate
  limiting. A name that resolves into a trusted range counts as a proxy and the walk goes on, so one header can cause
  one lookup per entry. The walk starts only when the peer is trusted and stops at the first untrusted entry: names to
  the left of an untrusted address, such as the real client address the ingress appends, are never resolved. A regex
  value never resolves names; a CIDR list in `trusted-proxies` resolves them in the same way. A fix is tracked as a
  backlog item.
- Override per environment with `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`: a comma-separated CIDR list (e.g.
  `10.1.0.0/16`) or, if the value contains no `/`, a single Java regex. The regex must match the whole peer IP, and IPv6
  peers appear uncompressed (`0:0:0:0:0:0:0:1`), which is why CIDR is preferred despite the DNS lookups above. Setting
  the value replaces the default entirely, so loopback stops being trusted unless it is listed.
```

#### F3 Operator checklist: NetworkPolicy instead of an ingress CIDR (C6)
File: `docs/security/FORWARDED_HEADERS.md`

Current:
```text
- Once NetworkPolicies exist, narrow `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to the ingress pod CIDR.
```

New:
```text
- Do not try to narrow `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to the ingress pods: no address range holds only them.
  Pod addresses come from a per-node range that every pod on the node shares (k3s: `10.42.0.0/16`, one `/24` per
  node), and a recreated pod gets a new address, possibly on another node. A node range trusts every pod on that node.
  When the ingress runs outside the listed range, its requests come from an untrusted peer: every client gets the
  ingress address (one shared rate-limit bucket) and no request is secure (no HSTS).
- Limit who can connect instead: once NetworkPolicies exist, admit port 8080 of the api pods only from the ingress
  controller's pods.
```

#### D1 Roles table: default privileges cover only migrator's tables (C7)
File: `docs/deploy/DATABASE.md`

Current:
```text
| `app_rw` | NOLOGIN group role | `USAGE` on `core`; `SELECT/INSERT/UPDATE/DELETE` on its tables; `USAGE/SELECT` on its sequences (V2 plus default privileges for later tables); no access to `flyway_schema_history` | Granted to the runtime login role |
```

New:
```text
| `app_rw` | NOLOGIN group role | `USAGE` on `core`; `SELECT/INSERT/UPDATE/DELETE` on its tables; `USAGE/SELECT` on its sequences (V2 plus default privileges for later tables that `migrator` creates, see [Default privileges](#default-privileges)); no access to `flyway_schema_history` | Granted to the runtime login role |
```

#### D2 Extension upgrades section (C8)
File: `docs/deploy/DATABASE.md`

Current:
```text
## Where each environment provisions them
```

New:
```text
## Extension upgrades

`vector` belongs to the superuser that created it, so `migrator` cannot update it
(`must be owner of extension vector`). Migrations never alter or drop extensions; the `CREATE EXTENSION IF NOT EXISTS`
in V1 does nothing because the extension already exists. After a Postgres upgrade that ships a newer pgvector, a
superuser runs `ALTER EXTENSION vector UPDATE;` in the application database before the migrate Job of the first
release that needs the new version.

## Where each environment provisions them
```

#### D3 Define <version> (C9)
File: `docs/deploy/DATABASE.md`

Current:
```text
Run `java -jar services/core/build/libs/core-<version>.jar migrate`, or the image with argument `migrate`. The exit
code is 0 or 1. It writes one wide event (`work.kind=job`) to the console. In Kubernetes a Helm pre-install/pre-upgrade
Job (task 009) runs it before new pods start, and api/worker pods set `SPRING_FLYWAY_ENABLED=false`. It fails loudly
when `app_rw` is missing (V2) or Flyway is disabled.
```

New:
```text
Run `java -jar services/core/build/libs/core-<version>.jar migrate`, or the image with argument `migrate`. `<version>`
is the Gradle project version (`version` in `gradle.properties`, for example `0.1.0-SNAPSHOT`), and
`./gradlew :services:core:bootJar` builds the jar. The exit code is 0 or 1. It writes one wide event (`work.kind=job`)
to the console. In Kubernetes a Helm pre-install/pre-upgrade Job (task 009) runs it before new pods start, and
api/worker pods set `SPRING_FLYWAY_ENABLED=false`. It fails loudly when `app_rw` is missing (V2) or Flyway is disabled.
```

#### D4 Default privileges section (C7; amended after review round 1)
File: `docs/deploy/DATABASE.md`

Current:
```text
## Environment variables
```

New (fenced with four backticks because the text contains a `sql` code block; copy the lines between the fences):
````text
## Default privileges

V2 grants `app_rw` access to the tables and sequences that exist, and runs `ALTER DEFAULT PRIVILEGES` for later ones.
Without `FOR ROLE`, Postgres records these defaults only for the role that ran V2 (`migrator`). A table or sequence in
`core` that another role creates gets no `app_rw` grants, and the app fails on it with
`permission denied for table <table>` or `permission denied for sequence <sequence>` (`<table>` and `<sequence>` are
the object names).

- Every migration runs as `migrator`, the role whose default privileges V2 recorded. A credential rotation changes its
  password; it never replaces the role. Never set `FLYWAY_USERNAME` to another login, for example a superuser, except
  to the new owner in step 2 of [Changing the schema owner](#changing-the-schema-owner).
- Never create objects in `core` by hand as another role. If it happened, a superuser grants them explicitly:
  `GRANT SELECT, INSERT, UPDATE, DELETE ON core.<table> TO app_rw;` and, for a sequence,
  `GRANT USAGE, SELECT ON SEQUENCE core.<sequence> TO app_rw;`. Changing the owner to `migrator` later does not add
  the grants.

### Changing the schema owner

`<new owner>` is the name of the role that takes over from `migrator`. Do steps 1 and 2 with no deploy between them.
The running app is not affected.

```sql
REASSIGN OWNED BY migrator TO <new owner>;
ALTER DEFAULT PRIVILEGES FOR ROLE <new owner> IN SCHEMA core GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE <new owner> IN SCHEMA core GRANT USAGE, SELECT ON SEQUENCES TO app_rw;
```

1. As a superuser, run the SQL above in the application database. `REASSIGN OWNED` moves the database, the schema and
   its objects with their grants, but not the default privileges. The two `ALTER DEFAULT PRIVILEGES` statements are
   those of V2 with `FOR ROLE <new owner>`; they record the default privileges for `<new owner>`.
2. Run the migrate command as `<new owner>` from now on: set `FLYWAY_USERNAME` and `FLYWAY_PASSWORD`, or
   `database.migratorSecret` in the Helm chart. A migrate run as `migrator` after step 1 fails with
   `permission denied for schema core`.
3. To remove `migrator`, run `DROP OWNED BY migrator;` and then `DROP ROLE migrator;` as a superuser. The old default
   privileges still belong to `migrator` and block `DROP ROLE` until then. Never run `DROP OWNED` before step 1: it
   drops the schema and its tables.

## Environment variables
````

#### D5 Environment table: FLYWAY_USERNAME is the schema owner (C7; amended after review round 1)
File: `docs/deploy/DATABASE.md`

Current:
```text
| `FLYWAY_USERNAME`, `FLYWAY_PASSWORD` | Schema owner (`migrator`); only the migrate process |
```

New:
```text
| `FLYWAY_USERNAME`, `FLYWAY_PASSWORD` | Schema owner: `migrator`, or the new owner role after [Changing the schema owner](#changing-the-schema-owner); only the migrate process |
```

#### W1 Define <release> (C9)
File: `docs/observability/WIDE_EVENT_SCHEMA.md`

Current:
```text
The `migrate` command writes its event to the console only (no OTLP export); read it with `kubectl logs job/<release>-migrate`.
```

New:
```text
The `migrate` command writes its event to the console only (no OTLP export); read it with `kubectl logs job/<release>-migrate`.
`<release>` is the chart fullname, as defined in the [chart README](../../deploy/helm/tastebuds-core/README.md).
```

#### S1 Known gaps (C10)
File: `SECURITY.md`

Current:
```text
- RFC 9457 problem+json for application errors, including 401/403 from Spring Security and 413 for oversized bodies; internals and stack traces never leave the server. Known gaps: CORS rejections and errors raised by the servlet container before the application (e.g. oversized headers) use the default body
```

New:
```text
- RFC 9457 problem+json for application errors, including 401/403 from Spring Security and 413 for oversized bodies; internals and stack traces never leave the server. Known gaps: CORS rejections; responses that Spring Boot's `/error` endpoint renders as its default JSON, for example the 400 (without `X-Content-Type-Options`) for requests the Spring Security firewall rejects, such as a `;`, `//` or an encoded `.` in the path; and errors raised by the servlet container before the application, such as oversized headers or an encoded `/` in the path (Tomcat's HTML error page)
```

#### K1 KDoc (C10)
File: `services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/web/ProblemDetailsAdvice.kt`

Current:
```text
 * Every error leaves as RFC 9457 problem+json. Unexpected errors never reveal internals, only a trace ID that support
 * can look up. The wide event records the error identity; the stack trace is logged once, correlated by trace ID.
```

New:
```text
 * Turns exceptions from Spring MVC request handling into RFC 9457 problem+json. Responses written outside MVC (filters,
 * Spring Security handlers) use [ProblemResponses]; the error responses that are not problem+json are listed under
 * "Known gaps" in SECURITY.md. Unexpected errors never reveal internals, only a trace ID that support can look up. The
 * wide event records the error identity; the stack trace is logged once, correlated by trace ID.
```

#### Y1 Comment above rate-limit (C11)
File: `services/core/src/main/resources/application.yaml`

Current:
```text
  rate-limit:
```

New:
```text
  # Changing capacity, refill-tokens or refill-period (top level or under session) changes the Redis key prefix of
  # those buckets: every client, an attacker too, gets a new full bucket, and during a rolling deploy old and new pods
  # count in separate buckets.
  rate-limit:
```

#### B1 Backlog item rewrite (C10; amended after review round 1)
File: `docs/backlog/2026-11-15-non-problem-json-error-paths.md`

Current:
```text
# Some error paths still bypass problem+json
- **Found during:** planning of task 003 (external review of Phase 0)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/security/SecurityConfiguration.kt (CORS), Spring Boot `/error` (BasicErrorController)
- **Severity:** low
- **What:** After task 003, 401/403/413 and MVC exceptions are problem+json, but (a) CORS rejections (`CorsFilter` /
  `DefaultCorsProcessor`, 403 `Invalid CORS request` as text/plain) and (b) errors raised by Tomcat before the
  application (oversized headers, malformed requests) rendered by Boot's `/error` endpoint as Boot's default JSON are not.
- **Why it matters:** The API's documented error contract has exceptions; clients need special cases.
- **Suggested fix:** Custom `CorsProcessor` that writes problem+json via `ProblemResponses`; an `ErrorController`
  (or `ErrorAttributes`) that renders `/error` as RFC 9457 without messages. Then drop the "Known gaps" note in SECURITY.md.
```

New:
```text
# Some error paths still bypass problem+json
- **Found during:** planning of task 003 (external review of Phase 0); corrected by task 013 (spec audit 2026-11-21)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/security/SecurityConfiguration.kt (CORS), Spring Security `FilterChainProxy` (firewall), Spring Boot `/error` (BasicErrorController), Tomcat `ErrorReportValve`
- **Severity:** low
- **What:** After task 003, 401/403/413 and MVC exceptions are problem+json, but these responses are not:
  (a) CORS rejections (`CorsFilter` / `DefaultCorsProcessor`): 403, body `Invalid CORS request`, no `Content-Type`;
  (b) requests the Spring Security firewall (`StrictHttpFirewall`) rejects, for example a `;`, `//` or an encoded `.`
  in the path: `HttpStatusRequestRejectedHandler` calls `sendError(400)`, and Boot's `/error` writes its default JSON
  `{timestamp,status,error,path}` without `X-Content-Type-Options`;
  (c) errors that Tomcat raises before the application, for example oversized headers or an encoded `/` in the path:
  Tomcat writes its own text/html 400 page, and the request never reaches `/error`.
- **Why it matters:** The API's documented error contract has exceptions; clients need special cases.
- **Suggested fix:** Custom `CorsProcessor` that writes problem+json via `ProblemResponses`; an `ErrorController`
  (or `ErrorAttributes`) that renders `/error` as RFC 9457 without messages, for (b); a Tomcat-level change for (c),
  for example a custom `ErrorReportValve`, because an `ErrorController` never sees these requests. Then drop the
  "Known gaps" note in SECURITY.md.
```

#### Y2 Comment above forward-headers-strategy (C5, C6; added after review round 1)
File: `services/core/src/main/resources/application.yaml`

Current:
```text
  # Behind a TLS-terminating ingress. X-Forwarded-For/-Proto are honoured only from internal proxies, see
  # docs/security/FORWARDED_HEADERS.md. Narrow the trusted range per environment with
  # SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES (comma-separated CIDR list, e.g. 10.1.0.0/16; replaces the default).
```

New:
```text
  # Behind a TLS-terminating ingress. X-Forwarded-For/-Proto are honoured only from internal proxies, see
  # docs/security/FORWARDED_HEADERS.md. SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES replaces Boot's default trusted range
  # (comma-separated CIDR list, e.g. 10.1.0.0/16). A CIDR list can make Tomcat resolve host names from X-Forwarded-For.
  # No range holds only the ingress pods: limit who can connect to the api pods with a NetworkPolicy instead.
```

#### M1 Backlog item on sidecar meshes (C6; added after review round 1)
File: `docs/backlog/2026-11-18-mesh-sidecar-internal-proxies.md`

Current:
```text
# Narrowing internal-proxies to the ingress CIDR breaks once a sidecar mesh is added
- **Found during:** 004 (run-task round 1 review)
- **Location:** docs/security/FORWARDED_HEADERS.md (operator checklist), docs/PLAN.md §8.1 (Linkerd, Phase 6)
- **Severity:** low
- **What:** With a sidecar proxy, the app's TCP peer is the local proxy (loopback), not the ingress pod. Narrowing
  `internal-proxies` to the ingress CIDR would then stop honouring forwarded headers entirely.
- **Why it matters:** Either client IP/scheme are lost (HSTS off, rate limiting per pod) or the range is widened again.
- **Suggested fix:** When Linkerd lands, revisit: trust loopback only, and enforce who can reach the pod via NetworkPolicy
  / mesh authorization policy instead of the IP range.
```

New:
```text
# Forwarded-header trust needs a review when a sidecar mesh is added
- **Found during:** 004 (run-task round 1 review); updated by task 013 (spec audit 2026-11-21)
- **Location:** docs/security/FORWARDED_HEADERS.md (operator checklist), docs/PLAN.md §8.1 (Linkerd, Phase 6)
- **Severity:** low
- **What:** With a sidecar proxy, the app's TCP peer is the local proxy (loopback), not the ingress pod. Since task 013,
  FORWARDED_HEADERS.md no longer advises narrowing `internal-proxies` to the ingress: it keeps Boot's default range,
  which includes loopback, and limits who can connect with a NetworkPolicy. Forwarded headers therefore keep working
  with a mesh, but the trust then rests only on who can reach the pod.
- **Why it matters:** If the NetworkPolicy does not fit the mesh's traffic, or is missing, any workload that reaches the
  api pods through the mesh can choose its client address and scheme.
- **Suggested fix:** When Linkerd lands, revisit: trust loopback only, check that the NetworkPolicy still admits only
  the ingress, and add the mesh's authorization policy.
```

## Assumptions verified
All runs on 2026-11-21 in a `mktemp -d` directory outside the repo, with the locked library versions
(`services/core/gradle.lockfile`: spring-boot 4.1.2, tomcat-embed-core 11.0.27, spring-security-web 7.1.2,
bucket4j 8.22.0) from the Gradle cache and JDK 21.

| Claim | How checked |
|---|---|
| The number in `TASTEBUDS_ANONSESSION_SIGNINGKEYS_<n>_*` is the list index: `_0_`+`_1_` bind two keys in index order; only `_1_`, `_0_`+`_2_`, only `_00_`, and `_0_`+`_01_` fail with `UnboundConfigurationPropertiesException` ("The elements [...signingkeys[1].id, ...] were left unbound"); no variables bind an empty list | Spring Boot 4.1.2 `Binder` over a `SystemEnvironmentPropertySource` named `systemEnvironment` (the relaxed env mapping that `@ConfigurationProperties` uses), binding a list of `(id, secret)` records |
| The `api` role does not start without a key; the `worker` role does not bind these properties at all | `AnonSessionConfiguration` is `@ConditionalOnRole(Role.API)` with `@EnableConfigurationProperties`; `AnonSessionTokens.fromProperties` throws for an empty list; existing test `AnonSessionConfigurationTest` "the api role without a key fails startup" |
| `openssl rand -base64 32` writes 45 bytes (44 + `\n`); `Base64.getDecoder()` rejects it ("Input byte array has incorrect ending byte at 44"); with `\| tr -d '\n'` it decodes to 32 bytes; the startup message is `tastebuds.anon-session.signing-keys[<n>].secret must be Base64 of at least 32 bytes` | OpenSSL 3.0.2 on the host plus a scratch Java decoder; `AnonSessionProperties.kt:31,42-47`; existing test "invalid settings fail startup without showing the secret" covers the message |
| The Binder keeps a trailing newline in an env value; `TASTEBUDS_ANONSESSION_TTL=45d` binds `tastebuds.anon-session.ttl` | Same `Binder` probe (bound secret ends with `\n`; ttl `PT1080H`) |
| A Secret created with `kubectl create secret generic --from-file` keeps the file's trailing newline, and `envFrom` passes it into the container env | `rancher/k3s:v1.35.10-k3s1` (task 010's pin) in Docker; secret data decoded with `od -c`; `printenv \| od -c` in a busybox pod shows `=\n` |
| With `maxSurge: 1, maxUnavailable: 0`, a new pod that fails at startup stops the rollout and both old pods stay Running and Ready | Same k3s: Deployment with 2 replicas, patched to `exit 1`; `kubectl rollout status --timeout=60s` exit 1; status 3 total, 2 ready, 1 updated. The chart uses the same strategy (`deployment-api.yaml:16-20`) |
| k3s gives each node one range from the cluster range and every pod on the node gets an address from it; a recreated pod gets a new address | Same k3s: flannel `net-conf.json` `"Network": "10.42.0.0/16"`, node `spec.podCIDR` `10.42.0.0/24`, coredns, local-path-provisioner and test pods all `10.42.0.x`; pod recreated `10.42.0.4` → `10.42.0.8` |
| A token expires at issue time + the ttl in force at issue; ttl 1 h to 90 d; a token with an unknown key id gets the same 401 | `AnonSessionTokens.kt:68`, `AnonSessionProperties.kt:25`; existing test `AnonSessionTokensTest` "tokens with an unknown key id are rejected" |
| Boot's default `internal-proxies` is a CIDR list, passed to `RemoteIpValve.setInternalProxies`; a value with `/` after index 0 becomes a `NetMaskSet`, else a regex; the valve is an engine valve, so it runs before the servlet filters (and the rate limiter inside the Spring Security chain) | spring-boot-tomcat 4.1.2 sources `TomcatServerProperties.java:744`, `TomcatWebServerFactoryCustomizer.java:264-266` (`addEngineValves`); tomcat-embed-core 11.0.27 `RemoteIpValve.java:881-891` |
| DNS behaviour of the valve: (A) a name right of a public IP: one lookup, it resolves into a trusted range, the walk goes on, client = the public IP; (B) a name left of a public IP: no lookup; (C) regex `internal-proxies`: no lookup; (D) untrusted peer: no walk, no lookup, client = peer; (E) four names: four lookups; (F) unresolvable name: one lookup, the walk stops, client = the name; (G) regex internal plus CIDR `trusted-proxies`: lookup | Embedded Tomcat 11.0.27 with `RemoteIpValve` set up as Boot does (Boot's default CIDR string), a logging `InetAddressResolverProvider`, requests from 127.0.0.1; source `RemoteIpValve.java:593-622,769-801`, `NetMaskSet.java:75-78` (`InetAddress.getByName`) |
| An untrusted peer's requests get `remoteAddr` = peer and are not secure | Case D above; HSTS only for secure requests and rate limiting by `remoteAddr` are existing statements of FORWARDED_HEADERS.md "Consumers" (task 004) |
| V2 run as `migrator` records default privileges for `migrator` only: a table by `migrator` gets `app_rw` DML; a table and sequence by the superuser get none and the app login gets `permission denied for table t_superuser`; `ALTER TABLE ... OWNER TO migrator` afterwards adds nothing; an explicit `GRANT` fixes it | `pgvector/pgvector:0.8.7-pg17` (the Testcontainers image) with `postgres-init.sql`, schema and V2 applied as `migrator`; `pg_default_acl` two rows with `defaclrole = migrator` |
| `REASSIGN OWNED BY migrator TO owner2` moves database and schema ownership but not `pg_default_acl`; `DROP ROLE migrator` fails ("owner of default privileges on new relations belonging to role migrator") until `DROP OWNED BY migrator`; `DROP OWNED BY migrator` before the reassign drops schema `core` | Same image, superuser and login sessions with `psql` |
| Owner change of D4 (re-verified after review round 1): the superuser runs `REASSIGN OWNED BY migrator TO owner2` and both `ALTER DEFAULT PRIVILEGES FOR ROLE owner2 IN SCHEMA core ...`; `pg_default_acl` then has rows for owner2; the app login still inserts into a table created before, and still gets `permission denied for table flyway_schema_history`; `migrator` gets `permission denied for schema core`; owner2, without running any statement itself, creates a table with a `bigserial` that the app can insert into, alters an old table and writes the history; after `DROP OWNED BY migrator` and `DROP ROLE migrator` the app still inserts into the old table | Same image, `psql` sessions as superuser, `migrator`, owner2 and `tastebuds_app` |
| With real Flyway (12.4.0, as locked): after step 1 a start with `FLYWAY_USERNAME=migrator` fails with `permission denied for schema core`; with `FLYWAY_USERNAME=owner2` Flyway logs "Successfully validated 3 migrations" and the app starts | The local jar (Flyway runs at startup in that build) against the same image: one run as `migrator` before step 1, then one as `migrator` and one as owner2 after it |
| A sequence that another role created gives the app `permission denied for sequence <sequence>`, both through `nextval` and through a column default on a granted table | Same image: sequence and table by the superuser, `GRANT SELECT, INSERT` on the table only, `INSERT ... DEFAULT VALUES` and `SELECT nextval(...)` as `tastebuds_app` |
| A container restart in an old pod reads the edited Secret: after removing key 0 and adding key 1, the restarted container of the same pod saw `id0=unset id1=k2` (a gap) | `rancher/k3s:v1.35.10-k3s1` in Docker: Deployment with `envFrom` a Secret, a container that exits after 8 s, Secret patched while the pod lives |
| Old pods sign with `keys.first()` only (index 0); every listed key verifies | `AnonSessionTokens.kt:58-63` |
| A CORS rejection is 403 with the body `Invalid CORS request` and no `Content-Type` header (it carries the hardened headers) | The local jar: `GET` and preflight `OPTIONS` to `/v1/meta` with `Origin: https://evil.example` (empty allow-list), `curl -D -` |
| `vector` is owned by the superuser (`extowner = postgres`); 0.8.7 is `superuser = t, trusted = f`; as `migrator`, `ALTER EXTENSION vector UPDATE` (and `UPDATE TO '0.8.7'`) fails with `must be owner of extension vector`; as superuser it gives a NOTICE; `CREATE EXTENSION IF NOT EXISTS vector` as `migrator` only NOTICEs | Same image |
| The jar is `services/core/build/libs/core-<version>.jar` with `<version>` from `gradle.properties` | `gradle.properties` `version=0.1.0-SNAPSHOT`; existing `services/core/build/libs/core-0.1.0-SNAPSHOT.jar`; Dockerfile `COPY build/libs/core-*.jar` and its `./gradlew :services:core:bootJar` comment |
| The migration Job is `<chart fullname>-migrate`; the chart README defines `<release>` as the chart fullname; the relative link resolves from `docs/observability/` | `job-migrate.yaml:7`; chart README lines 14-15; `ls ../../deploy/helm/tastebuds-core/README.md` from `docs/observability` |
| `GET /v1/meta;x=1`, `/v1//meta`, `/v1/%2e%2e/meta`, `/v1/meta%3Bx` get 400 `application/json` `{"timestamp","status":400,"error":"Bad Request","path"}` without `X-Content-Type-Options` (the 200 has it) and produce a wide event; `GET /v1%2Fmeta` and a 20 KB header get 400 `text/html` with Tomcat's `ErrorReportValve` page and no wide event | Ran the local jar `services/core/build/libs/core-0.1.0-SNAPSHOT.jar` (built at a19daee, task 004) against the pgvector container; it bundles the same spring-security-web, tomcat and spring-boot versions as the lockfile, and `main` adds no firewall, `ErrorController` or valve customisation (the `SecurityConfiguration` diff since then changes only authorization rules and comments). Source: `FilterChainProxy` defaults to `StrictHttpFirewall` and `HttpStatusRequestRejectedHandler` (`sendError(400)`); `ErrorReportValve.java:247` writes the HTML seen |
| `ProblemResponses.write` is used by `RateLimitFilter` (429), `RequestBodyLimit` (413), `AnonSessionAuthentication` (401) and `ProblemDetailsSecurityHandlers` (401, 403) | grep in `services/core/src/main` |
| The bucket key prefix contains capacity, refill tokens and refill period (not the cost), so a change gives new keys; a new bucket starts full | `RedisRateLimiter.kt:149-155`; Bucket4j 8.22.0 bucket with the same configuration (capacity 120, greedy refill): 120 available tokens |
| The comments in `application.yaml` (Y1, Y2) change no data | `mikefarah/yq:4.44.3` in Docker: JSON of the old and the edited file identical |
| The spec's own checks: each of the 32 original grep checks fails on `origin/main` in the expected direction; each grep added or changed after review round 1 fails on the round 1 worktree and passes after round 2; all checks pass on a scratch copy with all 17 edits applied; the verbatim check prints nothing there; the edits give the same result in forward and reverse order; added lines are at most 120 characters, except table rows, the SECURITY.md bullet, the WIDE_EVENT_SCHEMA.md sentence and the backlog `Location` line, which keep the existing one-line style | Python script that reads the edit blocks of this spec, applies them to copies of the `origin/main` files in a scratch git repo, then runs the commands of the acceptance criteria; the same greps run in the round 1 worktree |

## Limits
| Limit | Value | Wrapped by (must be larger) |
|---|---|---|
| Token lifetime `tastebuds.anon-session.ttl` (documented in A2) | 30 d default, 1 h to 90 d | Wait before rotation step 3: the longest ttl in force while the old key signed, plus 1 h, counted from the end of the step 2 rollout |
| Old pods that still answer after the rollout reports done (shutdown) | `terminationGracePeriodSeconds` 45 s (chart default), `preStop` sleep 5 s | The 1 h margin in step 3. Whether `kubectl rollout status` counts terminating pods was not checked; the margin covers both cases |
| `X-Forwarded-For` entries the valve may resolve per request (documented in F2) | One lookup per entry; the header limit `server.max-http-request-header-size: 16KB` bounds the entry count | Nothing in the app bounds the time of the lookups (no request deadline in `application.yaml`, no valve customisation). F2 states the risk; the mitigation is backlog (audit F6) |

## Acceptance criteria
Run every command from the repository root on the task branch.

1. All 17 edits are applied. This prints nothing (the checks marked `# r2` were added or changed after review round 1):
   ```bash
   check() { n=$(grep -Fc -- "$2" "$1"); [[ "$n" == "$3" ]] || echo "FAIL $1: '$2' found $n times, expected $3"; }
   checkx() { n=$(grep -Fxc -- "$2" "$1"); [[ "$n" == "$3" ]] || echo "FAIL $1: line '$2' found $n times, expected $3"; }
   A=docs/security/ANON_SESSIONS.md F=docs/security/FORWARDED_HEADERS.md D=docs/deploy/DATABASE.md
   check $A 'is the index of the key in the list: index 0 signs new tokens' 1
   check $A 'no gap and no leading zero (`_01_` is not index 1)' 1
   check $A 'openssl rand -base64 32 | tr -d' 1
   check $A '`kubectl create secret generic --from-file` keeps the newline' 1
   check $A 'then wait one token lifetime and one hour more' 1
   check $A 'environment variable `TASTEBUDS_ANONSESSION_TTL`' 1
   checkx $A '## Leaked key' 1
   check $A 'works only when it has the highest index and another key remains' 1
   check $A 'A leaked key: remove it at once' 0
   check $A 'and if it is at index 0 they also sign new tokens with it' 1  # r2
   check $A 'reads the broken list and fails at startup too' 1  # r2
   check $A 'The old pods keep running, accept the leaked key' 0  # r2
   check $F '**DNS lookups:** with a CIDR list' 1
   check $F 'a CIDR list in `trusted-proxies` resolves them in the same way' 1
   check $F 'which is why CIDR is preferred despite the DNS lookups above' 1
   check $F 'no address range holds only them' 1
   check $F 'admit port 8080 of the api pods only from the ingress' 1
   check $F 'to the ingress pod CIDR' 0
   check $F 'can be narrowed to the' 0
   checkx $D '## Default privileges' 1
   check $D 'see [Default privileges](#default-privileges)' 1
   check $D 'REASSIGN OWNED BY migrator TO <new owner>;' 1
   check $D 'Never run `DROP OWNED` before step 1' 1
   checkx $D '### Changing the schema owner' 1  # r2
   check $D 'ALTER DEFAULT PRIVILEGES FOR ROLE <new owner> IN SCHEMA core GRANT USAGE, SELECT ON SEQUENCES TO app_rw;' 1  # r2
   check $D 'Do steps 1 and 2 with no deploy between them' 1  # r2
   check $D 'A migrate run as `migrator` after step 1 fails with' 1  # r2
   check $D 'to the new owner in step 2 of [Changing the schema owner](#changing-the-schema-owner)' 1  # r2
   check $D 'permission denied for sequence <sequence>' 1  # r2
   check $D 'Add a migration that starts with' 0  # r2
   checkx $D '## Extension upgrades' 1
   check $D '(`must be owner of extension vector`)' 1
   check $D 'is the Gradle project version' 1
   check $D 'or the new owner role after [Changing the schema owner](#changing-the-schema-owner); only the migrate process' 1  # r2
   check docs/observability/WIDE_EVENT_SCHEMA.md '`<release>` is the chart fullname' 1
   check SECURITY.md 'for requests the Spring Security firewall rejects' 1
   check SECURITY.md 'use the default body' 0
   check services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/web/ProblemDetailsAdvice.kt 'Turns exceptions from Spring MVC request handling' 1
   check services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/web/ProblemDetailsAdvice.kt 'Every error leaves as RFC 9457' 0
   check services/core/src/main/resources/application.yaml 'changes the Redis key prefix of' 1
   check services/core/src/main/resources/application.yaml 'limit who can connect to the api pods with a NetworkPolicy instead' 1  # r2
   check services/core/src/main/resources/application.yaml 'Narrow the trusted range per environment' 0  # r2
   check docs/backlog/2026-11-15-non-problem-json-error-paths.md 'HttpStatusRequestRejectedHandler' 1
   check docs/backlog/2026-11-15-non-problem-json-error-paths.md 'rendered by Boot' 0
   check docs/backlog/2026-11-15-non-problem-json-error-paths.md 'body `Invalid CORS request`, no `Content-Type`' 1  # r2
   check docs/backlog/2026-11-15-non-problem-json-error-paths.md 'as text/plain' 0  # r2
   check docs/backlog/2026-11-18-mesh-sidecar-internal-proxies.md 'no longer advises narrowing `internal-proxies` to the ingress' 1  # r2
   check docs/backlog/2026-11-18-mesh-sidecar-internal-proxies.md 'Narrowing internal-proxies to the ingress CIDR breaks' 0  # r2
   ```
2. No other text changed. This prints nothing, and the second command prints `9`:
   ```bash
   files="SECURITY.md docs/deploy/DATABASE.md docs/observability/WIDE_EVENT_SCHEMA.md docs/security/ANON_SESSIONS.md docs/security/FORWARDED_HEADERS.md services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/web/ProblemDetailsAdvice.kt services/core/src/main/resources/application.yaml docs/backlog/2026-11-15-non-problem-json-error-paths.md docs/backlog/2026-11-18-mesh-sidecar-internal-proxies.md"
   base=$(git merge-base origin/main HEAD)
   git diff -U0 "$base" -- $files | grep -vE '^(diff |index |--- |\+\+\+ |@@|\\)' | sed -E 's/^[-+]//' \
     | while IFS= read -r l; do grep -Fxq -- "$l" docs/tasks/013-docs-audit-fixes.md || echo "NOT IN SPEC: $l"; done
   git diff --name-only "$base" -- $files | wc -l
   ```
3. `scripts/agentic/scope.sh diff docs/tasks/013-docs-audit-fixes.md` exits 0 and prints `scope: all changes within`;
   in particular `V2__app_rw_grants.sql` is unchanged.
4. `application.yaml` parses to the same data as on `origin/main` (Y1 and Y2 add comments only). This prints `same`:
   ```bash
   d=$(mktemp -d); git show origin/main:services/core/src/main/resources/application.yaml > "$d/old.yaml"
   cp services/core/src/main/resources/application.yaml "$d/new.yaml"
   for f in old new; do docker run --rm -v "$d":/w:ro mikefarah/yq:4.44.3 -o=json '.' "/w/$f.yaml" > "$d/$f.json"; done
   cmp -s "$d/old.json" "$d/new.json" && echo same; rm -rf "$d"
   ```
5. The new link target exists: `test -f deploy/helm/tastebuds-core/README.md` (W1 links to it from
   `docs/observability/`), and the anchor `#default-privileges` of D1 matches the heading `## Default privileges` of
   D4 (checked by criterion 1).
6. `./gradlew check` passes.

## Required tests
None: no behaviour changes. The acceptance criteria commands are the checks; `./gradlew check` covers the KDoc (ktlint,
detekt) and loads `application.yaml` in the integration tests.

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- No Kotlin statement changes, so the coverage gates are unaffected.
- No wide event field is added (WIDE_EVENT_SCHEMA.md only gets the `<release>` definition).
- No new dependency. `mikefarah/yq:4.44.3` is used only by criterion 4, through Docker.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->

### Round 1: APPROVE (with spec gaps; amendment round follows)
Findings: none blocking. All acceptance criteria met (32 greps, word-for-word check, scope, yq `same`, anchor); `./gradlew check` BUILD SUCCESSFUL. The reviewer verified the key claims by running them: Spring Boot 4.1.2 Binder list binding (gaps, `_00_`, deleted index 0, trailing newline), `openssl rand -base64 32` + `tr -d '\n'`, Tomcat 11.0.27 `RemoteIpValve`/`NetMaskSet` DNS lookups with Boot's CIDR default, the default-privileges and REASSIGN/DROP OWNED behaviour in `pgvector/pgvector:0.8.7-pg17`, and the 400 error paths on the running app (`;`, `//`, `%2e` → Boot JSON; `%2F` and a 20 KB header → Tomcat HTML).
Spec gaps (wording in the spec; each needs a spec amendment because AC 2 requires word-for-word text):
1. rule 6: DATABASE.md owner change, gap between step 1 and step 2 (migrator gets `permission denied for schema core` on the next migrate run; the new migration must run as the new owner). Verified simpler procedure: the superuser also runs both `ALTER DEFAULT PRIVILEGES FOR ROLE <new owner> IN SCHEMA core ...` statements in step 1, then switches the credential; no migration, no window.
2. DATABASE.md: "never set FLYWAY_USERNAME to another login" contradicts step 2 of the procedure.
3. rule 1/6: ANON_SESSIONS.md leaked-key consequences overstated: old pods sign with the leaked key only when it is at index 0; an old pod that restarts during the stopped rollout reads the edited Secret (envFrom) and fails too.
4. FORWARDED_HEADERS.md refers to a DNS backlog item that is not on this branch (it is in PR #12, docs/audit-backlog): merge #12 before or with this task.
5. Backlog item non-problem-json (a): the CORS 403 has no Content-Type at all, not text/plain (checked with curl).
6. DATABASE.md: for a sequence the message is `permission denied for sequence <sequence>`.
Backlog suggestions: update 2026-11-18-mesh-sidecar-internal-proxies.md (FORWARDED_HEADERS.md now advises NetworkPolicy, not narrowing); align the application.yaml:48-50 comment with FORWARDED_HEADERS.md; chart README "Rotating a Secret" should link the new "Leaked key" section.
Orchestrator: spec gaps 1-3, 5, 6 and the first two backlog suggestions go to the planner as a spec amendment (all within the allowed paths, or docs/backlog), then implementer round 2. Gap 4 is a merge-order note. Chart README link → new backlog item (deploy/ is outside the allowed paths).
Parallel mode: run in worktree tastebuds_api-task-013 without the scope-guard hook (user decision).

### Round 2: APPROVE
Spec amended by the planner after round 1 (A2, D4, D5, B1 changed; Y2, M1 new; 17 edits); the implementer restored four files from origin/main and re-applied their edits. All six round 1 gaps closed and re-verified by running them: the owner change in pgvector/pgvector:0.8.7-pg17 with the real migrate command (migrate as migrator after step 1: exit 1, `permission denied for schema core`; as the new owner a scratch V3 applies and the app can use the new objects; DROP ROLE only after DROP OWNED); leaked-key consequences (old pods sign only with keys.first(); in-place envFrom Secret edit seen by a restarted container in k3s v1.35.10); CORS 403 without Content-Type (bootTestRun); sequence message. Y2 and M1 true and consistent with FORWARDED_HEADERS.md. A1, F1-F3, D1-D3, W1, S1, K1, Y1 byte-identical to round 1. AC1 (48 checks) prints nothing; AC2 count 9; AC4 `same`; AC5 anchors resolve; scope.sh exits 0; `./gradlew :services:core:test --rerun` BUILD SUCCESSFUL.
Open merge-order note: FORWARDED_HEADERS.md:29-30 points to docs/backlog/2026-11-21-remoteip-valve-dns-lookups.md, which lands in PR #12; merge #12 before or with this task.
Backlog: corrected 2026-11-21-chart-readme-leaked-key-link.md (section link only, exact procedure, path:line); created 2026-11-21-schema-owner-change-new-role-undefined.md and 2026-11-21-schema-owner-change-not-one-transaction.md (reviewer suggestions; changing the text needs a spec amendment because of AC2).
