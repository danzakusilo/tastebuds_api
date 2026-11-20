# Wide event schema

One `wide_event` log record per unit of work (HTTP request now; Kafka message and job later). It is the primary
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
| `work.kind` | string | lib | `http` (later `message`, `job`) |
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
| `auth.failure` | string | `unauthenticated` (401 from the entry point) or `access_denied` (403 from the access-denied handler); absent otherwise |

### Rate limiting (`/v1/**` only)
| Field | Type | Description |
|---|---|---|
| `rate_limit.outcome` | string | `allowed`, `rejected` (429) or `unavailable` (Redis unreachable or too slow; the request went through) |
| `rate_limit.cost` | long | Tokens this request costs |
| `rate_limit.remaining` | long | Tokens left in the client's bucket after this request; absent when `unavailable` |
| `rate_limit.rule` | string | Matching rule (`GET /v1/meta`, `* /v1/...`) or `default`. Needed because `http.route` is `UNMATCHED` on a 429 |
| `rate_limit.key_type` | string | `ip4`, `ip6` (the key is the /64) or `invalid` (unparsable address, one shared bucket) |

Never the client address or the bucket key (personal data).

### Reserved namespaces (to be filled by their modules)
`auth.*`, `principal.*` (`principal.tier`: `anon`, `user`, `admin`), `db.*`
(`db.query_count`), `llm.*` (`llm.model`, `llm.tokens_in`, `llm.tokens_out`, `llm.cost_usd`, `llm.cache_hit`),
`matching.*` (`matching.algo_version`, `matching.candidates`), `kafka.*`, `catalog.*`, `import.*`.
