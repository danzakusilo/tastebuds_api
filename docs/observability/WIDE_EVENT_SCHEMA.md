# Wide event schema

One `wide_event` log record per unit of work (HTTP request, migration job; Kafka message later). It is the primary
input for dashboards, alerts and the LLM digests (docs/PLAN.md §7), so **field names are an API**: add freely, rename
never (deprecate, then remove after 90 days, which is the digest retention).

## Transport
- Logger `wide_event`, message `wide_event`, fields as SLF4J key-value pairs.
- Console: ECS JSON. Dotted keys become nested objects (`http.route` → `{"http":{"route":...}}`).
- OTLP → Loki: attributes with dots replaced by underscores (`http_route`), queryable as structured metadata:
  `{service_name="tastebuds-core"} |= "wide_event" | http_response_status_code >= 500`
- Correlation: `traceId`/`spanId` (MDC, console) and `trace_id` (OTLP) link to the trace in Tempo.
- Level: `ERROR` when `outcome=error`, else `INFO`.

## Rules
- Keys: lowercase, dot-separated namespaces, `snake_case` segments (`^[a-z][a-z0-9_]*(\.[a-z0-9_]+)*$`). Invalid keys are dropped (never thrown) and counted in `wide_event.invalid_keys`.
- Values: scalars only (string ≤ 256 chars, number, boolean, enum → lowercase name). Others become `unsupported:<Type>`.
- No PII or secrets. Keys containing `password`, `passwd`, `secret`, `token`, `authorization`, `cookie`, `api_key`,
  `apikey`, `email`, `phone`, `ssn` are force-redacted (substring match: false positives are preferred over leaks). The
  only exemptions are the exact keys `llm.tokens_in` and `llm.tokens_out`; add new ones in `Redaction.kt` deliberately.
  No raw user input; no exception messages.
- Low cardinality: route templates, not paths; IDs only when needed for investigation, and then opaque ones.
- HTTP fields follow OpenTelemetry semantic conventions.

## Fields

### Common
| Field | Type | Source | Description |
|---|---|---|---|
| `work.kind` | string | lib | `http`, `job` (later `message`) |
| `outcome` | string | lib / handler | `success`, `client_error`, `error`; handlers may set others (e.g. `degraded`) |
| `duration_ms` | long | lib | Wall time of the unit of work |
| `error.class` | string | lib / advice | Exception class name |
| `error.fingerprint` | string | lib / advice | 12-hex hash of the root-cause type + top app frames; groups "the same bug" |
| `app.role` | string | core | `api`, `worker`, `all` |
| `wide_event.invalid_keys` | long | lib | Number of `put`/`increment` calls dropped for an invalid key (absent when 0) |

### HTTP (`work.kind = http`)
| Field | Type | Description |
|---|---|---|
| `http.request.method` | string | `GET`, `POST`, … |
| `http.route` | string | Matched route template (`/v1/media/{id}`), or `UNMATCHED` |
| `http.response.status_code` | int | Final status (500 if an exception escaped) |
| `http.request.body_too_large` | boolean | `true` when the request was rejected with 413 (absent otherwise) |

### Auth
| Field | Type | Description |
|---|---|---|
| `auth.failure` | string | `unauthenticated` (401 from the entry point), `access_denied` (403 from the access-denied handler) or `invalid_token` (401: the bearer token is malformed, signed with an unknown key, forged or expired); absent otherwise |
| `auth.failure_reason` | string | Only with `invalid_token`: `malformed`, `unknown_key`, `bad_signature` or `expired` |
| `principal.tier` | string | `anon`: the request carries a valid anonymous session token. Absent without credentials (`user`, `admin` from Phase 4) |

Never the token, the session id or a key id.

### Rate limiting (`/v1/**` only)
| Field | Type | Description |
|---|---|---|
| `rate_limit.outcome` | string | `allowed`, `rejected` (429) or `unavailable` (Redis unreachable or too slow; the request went through) |
| `rate_limit.cost` | long | Tokens this request costs |
| `rate_limit.remaining` | long | Tokens left after this request in the bucket that decided: the smaller of the session and IP buckets when allowed, the rejecting bucket when rejected; absent when `unavailable` |
| `rate_limit.rule` | string | Matching rule (`GET /v1/meta`, `* /v1/...`) or `default`. Needed because `http.route` is `UNMATCHED` on a 429 |
| `rate_limit.key_type` | string | `ip4`, `ip6` (the key is the /64) or `invalid` (unparsable address, one shared bucket) |
| `rate_limit.limited_by` | string | Only when `rejected`: `session` (the anonymous session's bucket) or `ip` (the client address bucket) |

Never the client address, the session id or the bucket key (personal data).

### Jobs (`work.kind = job`)
| Field | Type | Description |
|---|---|---|
| `job.name` | string | `migrate`: the Flyway migration command (docs/deploy/DATABASE.md) |
| `flyway.migrations_executed` | int | Migrations applied by this run (0 when the schema was current); absent when the run failed |
| `flyway.schema_version` | string | Schema version after the run, e.g. `2`; absent when the run failed or no migration was ever applied |

The `migrate` command writes its event to the console only (no OTLP export); read it with `kubectl logs job/<release>-migrate`.

### Reserved namespaces (to be filled by their modules)
`auth.*`, `principal.*`, `db.*`
(`db.query_count`), `llm.*` (`llm.model`, `llm.tokens_in`, `llm.tokens_out`, `llm.cost_usd`, `llm.cache_hit`),
`matching.*` (`matching.algo_version`, `matching.candidates`), `kafka.*`, `catalog.*`, `import.*`.
