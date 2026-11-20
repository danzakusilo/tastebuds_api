# Tastebuds API — Master Plan

> Living document. Changes to scope or architecture go through a PR that edits this file.
> Last revised: 2026-11-20 (client UX spec `tastebuds_spec`: D17–D20 added; §3.2 `library` module; §4 stars, library,
> reactions, picks; §5.5 user links publish at once; §6 client contract, `GET /v1/explore` dropped (no client screen; Discover deals
> user links only); Phases 3–5; risk register). Previous:
> 2026-11-15 (post-review: D3, D4, D12 revised; D15, D16 added; phases re-ordered).

## 1. Vision

Tastebuds helps people find new media to enjoy by connecting them with people who share their
*vibe*, not just their watchlist. The defining feature is **cross-media vibe links**:
*"this book reads like this Chopin nocturne sounds"*, *"this game feels like this film"*.

The backend is an open-source portfolio piece meant to carry real (small, maybe larger later) traffic, with
emphasis on **observability, iteration speed, agentic development, security, and scalability**.

### Product pillars
1. **Vibe space**: one media-agnostic representation in which a novel, an album, a game and a film can be compared.
2. **Explainable matching**: every match and recommendation says *why* ("you both loved X for its slow, lush melancholy").
3. **Low-friction entry**: anonymous users get value in under 60 seconds and keep their data when they sign up.
4. **Feedback-driven iteration**: every match carries an algorithm version, and user feedback ("Good match?") drives offline evaluation and A/B tests.

## 2. Decision log

| # | Decision | Choice | Notes |
|---|---|---|---|
| D1 | Media scope | **All media**: film, TV, anime, games, books, music (albums/tracks/works), podcasts | Cross-media links are a headline feature |
| D2 | Budget | **≤ $200/month** total (infra + LLM + embeddings) | Enforced in code by a cost meter, see §8.4 |
| D3 | Domain / edge | **None yet.** Local k3d first; buy a cheap domain before Phase 4 (identity needs it for verification email: SPF/DKIM/DMARC); Cloudflare free tier before public launch (Phase 6 gate) | Revised 2026-11-15: was "before launch", but email verification depends on it |
| D4 | Auth | **Spring Authorization Server** (in-house OIDC provider) as a separate deployable, built in **Phase 4**, after the anonymous vertical slices | Security boundary: separate image, DB schema, credentials. Revised 2026-11-15: product value first; anon sessions carry Phases 1–3 |
| D5 | Architecture | **Modular monolith** (Spring Modulith) + separate identity service. Core runs as `api` and `worker` roles from one image | Modules extractable later |
| D6 | Observability backend | Self-hosted Grafana LGTM (Loki/Tempo/Mimir/Grafana) via OTel Collector; exporter switch for New Relic / Grafana Cloud | |
| D7 | Retention | Raw telemetry **14 days**; LLM digests **90 days**; export on demand | |
| D8 | API style | REST + OpenAPI 3.1 (generated, committed, diff-checked in CI) under `/v1`; SSE for live feed later | |
| D9 | Agent loop | Planner **Opus** → Implementer **Sonnet** → Reviewer **Opus**, max 3 review rounds, then human handoff; scope enforced by a hook | See `docs/agentic/WORKFLOW.md` |
| D10 | Git | `main` branch, GitHub remote `danzakusilo/tastebuds_api`, CI on GitHub Actions | |
| D11 | Persistence | PostgreSQL 17 + pgvector; **jOOQ** (typesafe SQL, no string-built queries) + Flyway | |
| D12 | Messaging | Kafka (KRaft); **Spring Modulith event publication registry** (JDBC) + event externalization to Kafka instead of a hand-rolled outbox; idempotent consumers; DLQs | Strimzi on k8s. Revised 2026-11-15: Modulith already ships the outbox |
| D15 | Security baseline | **OWASP ASVS 5.0** Level 2 (+ selected L3 for auth/session) | Added 2026-11-15; ASVS 5.0 replaced 4.0 in May 2025 |
| D16 | Deployment target | **Kubernetes from Phase 1**: k3d locally, Helm chart for core, CloudNativePG; the chart is the deployment contract from day one | Added 2026-11-15; was deferred to the launch phase |
| D13 | Cache / limits | Redis (rate-limit buckets, anon sessions, hot caches) | |
| D14 | Music data | MusicBrainz / ListenBrainz (open data). **No Spotify-derived ML features** (Spotify's terms forbid it) | |
| D17 | Client UX source | **`tastebuds_spec`** (GitHub `danzakusilo/tastebuds_spec`): flows F01–F08, components C01–C02, glossary | Added 2026-11-20. Calls the spec marks *proposed* enter §6 before client work on them starts (the client builds ahead on fakes). This plan stays the product and domain source; on a conflict, stop and flag it |
| D18 | Ratings | **0.5–5 stars** in half steps, stored and shown; `sentiment` −2..+2 is **derived** from the rating | Added 2026-11-20 (spec F02 §7 Q3). Imports and the design system use stars. A take may have text and no rating |
| D19 | User-proposed vibe links ("connections") | **Publish at once**; reports and takedown come later. One per user per unordered media pair, note required | Added 2026-11-20 (spec F05 §7 Q1–Q4). Was "moderated" (§5.5). Every external link in user text opens behind a client warning dialog (spec C02) |
| D20 | Visibility | Every user's takes and links **feed matching and stats**. At sign-up the user picks **Public** or **Anonymous**: an anonymous account is never shown with a handle, avatar or id to others | Added 2026-11-20 (spec F05 §7 Q6, F08). Replaces "opt-in visibility" in §12; the sign-up notice discloses the use. Not the signed-out anon session |

## 3. Architecture

```
                 ┌───────────── Cloudflare (Phase 6: DDoS, WAF, bot mgmt, TLS) ─────────────┐
                 │                                                                          │
           ┌─────▼─────┐      Ingress (Envoy Gateway / NGINX): TLS, rate limits, size limits
           │  Ingress  │
           └──┬─────┬──┘
   OIDC/OAuth2│     │ REST /v1 (JWT bearer)
      ┌───────▼──┐ ┌▼───────────────┐ events (1)  ┌─────────┐   consume   ┌──────────────────┐
      │ identity │ │ core (role=api)│────────────▶│  Kafka  │────────────▶│ core (role=worker)│
      │ (SAS)    │ └──┬──────┬──────┘             └─────────┘             └──┬───────┬───────┘
      └────┬─────┘    │      │                                               │       │
           │       Postgres  Redis                                    LLM / embeddings / catalog APIs
      Postgres     +pgvector                                          (Claude Haiku, Voyage, TMDB,
      (identity                                                        IGDB, OpenLibrary, MusicBrainz…)
       schema)
                 all pods ── OTLP ──▶ OTel Collector (PII scrub, tail sampling) ──▶ LGTM | New Relic
                 in-cluster traffic: Linkerd mTLS + default-deny NetworkPolicies
```
(1) Spring Modulith event publication registry (transactional, in Postgres) externalized to Kafka (D12).

### 3.1 Gradle layout
```
build-logic/                 convention plugins (kotlin, spring-service, quality)
libs/observability/          wide events, MDC/trace correlation, log schema, PII redaction helpers
libs/security-common/        (Phase 1) shared security primitives (headers, problem+json for authN errors)
services/core/               modular monolith: api + worker roles
services/identity/           (Phase 1) Spring Authorization Server
deploy/                      compose (dev), helm charts, k3d config, otel-collector config
docs/                        plan, ADRs, tasks, backlog, threat model, runbooks
```

### 3.2 Core modules (Spring Modulith; boundaries verified in tests)
| Module | Responsibility |
|---|---|
| `catalog` | Canonical `Media`, external IDs, search, ingest from providers, dedup |
| `takes` | User opinions: star rating (sentiment derived), liked/disliked aspects, free text, spoiler flag, logged-on date |
| `library` | Saved media with a status (want to, in progress, done, not for me) and where it was saved from; private |
| `vibes` | Aspect extraction (LLM), vibe vectors (media + user), cross-media links |
| `matching` | Candidate generation (ANN), scoring, explanations, Discover ranking of user links |
| `recs` | Media recommendations from matches (the quick-match picks) |
| `social` | Profiles and visibility, follows, feed, comments, likes on takes, reactions on user links, blocks, reports |
| `imports` | CSV/API imports (Letterboxd, Goodreads, StoryGraph, Trakt, MAL, Steam, ListenBrainz) |
| `sessions` | Anonymous sessions, merge-on-signup |
| `feedback` | Match feedback, experiment assignment |
| `ops` | Telemetry digests, report export, admin |
| `shared` | IDs, clock, errors (no business logic; event publication is Spring Modulith's, see D12) |

Roles: `tastebuds.role=api|worker|all` (`all` for local dev). Kafka listeners and schedulers exist only in `worker`; HTTP controllers only in `api`.

### 3.3 Kafka topics (initial)
`catalog.media-ingested`, `takes.take-upserted`, `vibes.vectors-updated`, `matching.recompute-requested`,
`imports.job-submitted`, `social.activity`, `feedback.submitted` + a `.dlq` per consumer group.
Keys = aggregate ID (ordering per entity). Schemas: JSON Schema, versioned, contract-tested.

## 4. Domain model (sketch)

- **Media**(id, kind ∈ {FILM, TV, ANIME, GAME, BOOK, MUSIC_ALBUM, MUSIC_TRACK, MUSIC_WORK, PODCAST}, title, year, creators[], external_ids{}, description, catalog_vibe)
- **User**(id, handle, visibility ∈ {PUBLIC, ANONYMOUS}, bio?, preferences, trust_score) — identity lives in `identity`; core holds a projection
- **AnonSession**(id, expires_at, budget_remaining)
- **Take**(id, owner(user|anon), media_id, rating? 0.5–5, sentiment −2..+2 (derived from rating), liked_aspects[], disliked_aspects[], text?, spoiler, logged_on, origin ∈ {APP, IMPORT}, extracted_aspects, vibe_vector, extraction_status) — one per owner per media; a take with text is a *review*
- **VibeLink**(media_a, media_b, score, shared_dims[], source ∈ {COMPUTED, USER}, votes, author?, note?) — user-proposed links ("connections") are first-class content: symmetric, note required, one per author per unordered pair
- **LinkReaction**(user, vibe_link, reaction ∈ {LIKE, SAVE, PASS}, algo_version) — LIKE is the vote; SAVE and PASS come from the Discover deck and feed ranking
- **LibraryEntry**(owner, media_id, status ∈ {WANT, IN_PROGRESS, DONE, DROPPED}, source_link_id? | source_take_id? | source_import?, added_at, status_changed_at)
- **Match**(user_a, user_b, score, tier, explanation, algo_version, computed_at)
- **QuickMatch**(id, owner, status ∈ {QUICK, REFINING, REFINED, SKIPPED}, fallback, picks[media, tier ∈ {SAFE, LIKELY, POSSIBLE}, explanation, vibe_tags[], supporter_count], algo_version)
- **Follow**, **Comment**(on Take; editable by its author, removable by the take's owner), **TakeLike**, **FeedbackEvent**(match_id, verdict, reason?)
- **ImportJob**(id, owner, source, status, stats)

## 5. The vibe engine (cross-media core)

### 5.1 Two representations per take and per media
1. **Interpretable vibe dimensions** (~16 scalar axes, media-agnostic, each −1..+1):
   `energy`, `tempo/pacing`, `valence (bleak↔joyful)`, `tension`, `melancholy`, `warmth/intimacy`, `density/complexity`,
   `texture (sparse↔lush)`, `structure (linear↔fragmented)`, `realism↔fantastical`, `scale (intimate↔epic)`, `humor`,
   `romanticism`, `strangeness`, `nostalgia`, `craft-focus (form over content)`.
   Scored by the LLM against a fixed rubric, with **anchor examples per media kind**: what "lush texture" means for a
   symphony, a novel's prose, a game's art direction. Used for explanations and cross-media bridging.
2. **Dense embedding** (Voyage, ~1024-d) of a normalized "vibe statement" generated from the aspects. Used for recall (pgvector HNSW).

### 5.2 Media vibe
- **Catalog prior:** one LLM annotation per media from its description, genres and credits (cached forever, re-run only on rubric version bump). Gives every media a vibe before anyone rates it, which solves cold start.
- **Crowd posterior:** a Bayesian blend of the prior with users' take-level vibes, weighted by take count and rater trust.

### 5.3 User vibe profile
- Liked-vibe centroid(s) (k small clusters, since people have several tastes) + disliked-vibe centroid + per-kind breakdown.

### 5.4 Matching score (v1)
```
score(a,b) = w1·overlap_idf(a,b)          # shared media weighted by rarity (IDF), sentiment-aligned
           + w2·vibe_sim(liked_a, liked_b) # cross-media: works with zero shared titles
           + w3·aspect_agreement(shared)   # same media, same reasons
           − w4·dislike_conflict(a,b)
```
- Candidate generation: ANN over user profile vectors → top-K → exact re-rank → explanation builder.
- Tiers: **safe** (high score, high confidence), **likely** (high score, lower confidence), **possible** (moderate score, high novelty = vibe overlap in kinds the user hasn't explored).
- Every `Match` stores `algo_version`; experiments assign users to versions deterministically.

### 5.5 Cross-media links
- Computed: nearest neighbours in vibe space restricted to a *different* `kind`, keeping only pairs whose shared dims are strong (explainable).
- User-proposed: "X reads like Y", with the author's note as the reason (required); votes and trust weighting;
  published at once, reports and takedown later (D19).
- Surfaced on media pages, in explanations, and in "possible" tier recs.

### 5.6 LLM safety and cost
- User text is **data**: strict JSON-schema structured output, no tools, rubric in the system prompt, output validated and clamped.
- Async only (Kafka worker), never on the request path. Cache keyed by hash(normalized text + rubric version).
- Per-anon-session and per-user daily budgets; a global monthly cost meter with a circuit breaker (degrades to overlap + catalog-prior matching).
- Batch API for catalog annotation backfills, and for the text of imported reviews (under the cost meter).

## 6. API surface (v1, indicative)

Public/anonymous: `POST /v1/sessions/anonymous`, `GET /v1/media/search?q=&kind=&cursor=` (`kind` repeatable),
`GET /v1/media/{id}`, `GET`/`PUT`/`DELETE /v1/me/takes/{mediaId}`, `POST /v1/matches/quick`, `GET /v1/matches/{id}`
(partially redacted for anon), `POST /v1/imports` (anon: temporary, TTL), `GET /v1/imports/{id}`.
Authenticated: `GET /v1/feed`, `POST /v1/follows/{userId}`, `POST /v1/takes/{id}/comments`,
`POST /v1/matches/{id}/feedback`, `POST /v1/vibe-links`, `GET /v1/me/export`, `DELETE /v1/me`.
Admin: `GET /v1/admin/reports`, `POST /v1/admin/reports/export`.
Conventions: problem+json errors, cursor pagination, `Idempotency-Key` on writes, ETags on reads.

**Client contract (from `tastebuds_spec`, D17).** Shapes are in the linked spec sections; a task that builds one
copies it into OpenAPI.

| Area | Calls | Spec |
|---|---|---|
| Takes | `PUT /v1/me/takes/{mediaId}` `{rating?, text?, spoiler, loggedOn}` (D18); `GET` to prefill; `DELETE` | F01 §6, F07 §4 |
| Quick match | `POST /v1/matches/quick` (empty body: the session's takes) and `GET /v1/matches/{id}` return a `QuickMatch`: `status` QUICK → REFINING → REFINED or SKIPPED, `fallback`, `picks[]` with tier, explanation, vibe tags and supporter count; the client polls while REFINING (SSE later, D8) | F01 §6 |
| Me | `GET /v1/me` (summary incl. take count), `PUT /v1/me/visibility` `{PUBLIC \| ANONYMOUS}`, `PUT /v1/me/preferences` | F04 §3.3, F08 §2, F03 §5 |
| Feed | `GET /v1/feed?cursor=&types=&kinds=`: items `REVIEW` (a take with text) and `CONNECTION` | F02 §4 |
| Reviews | `GET /v1/takes/{id}`, `PUT`/`DELETE /v1/takes/{id}/like`, `GET /v1/takes/{id}/comments?cursor=`, `PATCH`/`DELETE /v1/comments/{id}` | F02 §3.4, §4 |
| People | `GET /v1/me/suggestions/people`, `DELETE /v1/follows/{userId}`, profile and its lists by handle | F02 §3.2, F05 §2 |
| Connections | `PATCH`/`DELETE /v1/vibe-links/{id}` (note only), `GET /v1/me/vibe-links?pair=` (duplicate check), `PUT`/`DELETE /v1/vibe-links/{id}/reaction` `{LIKE \| SAVE \| PASS}` with `algoVersion`, more links per media for the detail page | F05 §3–4, F03 §5 |
| Discover | `GET /v1/discover/connections?cursor=&kinds=`: cards with `reason`, `tasteMatch` (public authors only), author summary, `algoVersion` | F03 §5 |
| Library | `GET /v1/me/library?status=&kinds=&cursor=&sort=` (counts per status in the first page), `POST /v1/me/library/batch` `{mediaIds, status, sourceConnectionId? \| sourceTakeId?}`, `PATCH`/`DELETE /v1/me/library/{mediaId}` | F04 §5 |

Rules these calls enforce:
- Feeds carry only takes and links created in the app: never imported takes (also after an edit), never rating-only takes.
- Anonymous accounts (D20): every response read by another user has `author: null, anonymous: true`, and no handle,
  avatar, id or timestamp finer than a day; their profile is not found for others; they can't comment or be followed;
  lists count them and never list them.
- Library entries are private. Saving a media that is already in the Library never changes its status. A review's
  save count counts the saves made from that review only.
- Discover never shows the viewer's own links or links by people they follow; a passed link never returns.
- Comments are a flat list, oldest first; the take's owner may remove any comment on it.

## 7. Observability

- **Signals:** OpenTelemetry traces, metrics and logs (Spring Boot OTel starter + Micrometer), exported over OTLP.
- **Wide events:** exactly one structured `wide_event` log line per HTTP request / Kafka message / job, with a stable schema
  (`docs/observability/WIDE_EVENT_SCHEMA.md`): trace_id, route, status, duration_ms, principal_tier (anon|user|admin),
  module, outcome, error.class, error.fingerprint, algo_version, llm.tokens, llm.cost_usd, db.query_count, rate_limit.remaining …
  Business fields are added with `WideEvent.current().put(...)` anywhere in the call path.
- **Log schema:** JSON (ECS-compatible), no free-form interpolated secrets/PII. A redaction allow-list is applied in the app and again in the collector.
- **LLM digests** (`ops` module, worker role): scheduled hourly and daily, plus on demand. Queries Loki/Tempo/Mimir and emits
  compact Markdown + JSONL: top error fingerprints with sample traces, p50/p95/p99 regressions vs baseline, Kafka lag,
  LLM spend, matching-quality metrics (feedback approval rate per algo_version). Stored in S3-compatible storage
  (MinIO in-cluster) with 90-day lifecycle; `POST /v1/admin/reports/export` bundles a time range.
- **MCP server (Phase 4):** read-only telemetry/digest queries for Claude Code during incident work.
- **SLOs:** API availability 99.5%, p95 < 300 ms for reads; burn-rate alerts.

## 8. Security

Target baseline: **OWASP ASVS 5.0 Level 2** (D15) with selected L3 controls for auth and session. Full threat model in `docs/security/THREAT_MODEL.md` (STRIDE per component; written in Phase 1).

### 8.1 Perimeter and transport
- Cloudflare (DDoS L3–L7, WAF managed rules, bot fight mode) once a domain exists; origin accepts only Cloudflare IPs (+ authenticated origin pulls).
- TLS 1.3 only at the edge, HSTS preload, cert-manager + Let's Encrypt.
- Linkerd mTLS between all pods (covers MITM inside the cluster); default-deny NetworkPolicies; egress allow-list for external APIs.

### 8.2 Abuse and DoS at the app layer
- Bucket4j + Redis token buckets per IP, per anon session and per user, **cost-weighted** per endpoint (deep match ≫ search).
- Request size/time limits at ingress and in the app; bounded pagination; query timeouts in Postgres (`statement_timeout`).
- Anti-enumeration: uniform auth responses, opaque IDs (UUIDv7 externally only, never sequential).
- Scraping protection for profiles and matches: per-viewer view quotas; partial reveal computed on the server.

### 8.3 AuthN / AuthZ (identity service)
- Spring Authorization Server: OIDC, Authorization Code + PKCE only (no implicit, no password grant).
- Argon2id passwords, breached-password check (HIBP k-anonymity), passkeys (WebAuthn) + TOTP MFA.
- Short-lived access JWTs (5 min, ES256, rotating keys via JWKS), rotating refresh tokens with reuse detection → family revocation.
- Login throttling and lockout with exponential backoff, plus credential-stuffing detection (per-IP/ASN velocity).
- Core is an OAuth2 resource server; method-level authorization; object ownership checks tested per endpoint (BOLA/IDOR tests).

### 8.4 Data and injection
- jOOQ / bind parameters only; ArchUnit rule bans `String`-built SQL APIs; Semgrep rules in CI.
- Least-privilege DB roles: `app_rw` (no DDL), `migrator` (DDL, used only by the Flyway job), `readonly` (digests).
- Input validation at the edge (Bean Validation), output encoding, strict CORS allow-list, security headers (CSP, nosniff, frame-deny, referrer-policy).
- Imports: streaming CSV parser, size/row caps, formula-injection neutralization on export, no URL fetches (no SSRF surface).
- Encryption at rest (volume level) + column-level encryption for email/MFA secrets; GDPR export/delete; audit log (append-only).
- LLM: prompt-injection containment (§5.6); cost meter as DoS defence.

### 8.5 Supply chain and runtime
- Renovate, Gradle dependency verification + locking, Trivy (fs + image), CodeQL, gitleaks (pre-commit + CI), CycloneDX SBOM.
- Distroless/Chainguard JRE base images, non-root, read-only root FS, seccomp `RuntimeDefault`, Pod Security `restricted`.
- cosign-signed images; Kyverno admission policy verifies signatures.
- Secrets: External Secrets / SOPS; nothing secret in git (public repo).
- DAST: OWASP ZAP baseline against an ephemeral environment in CI.

## 9. Agentic development workflow

See `docs/agentic/WORKFLOW.md` (authoritative). Summary:
1. **Plan** (Opus, `planner` agent): writes `docs/tasks/NNN-slug.md` from `TEMPLATE.md`: goal, non-goals, **allowed paths**, acceptance criteria, required tests, coverage gate.
2. **Implement** (Sonnet, `implementer` agent): works only within allowed paths. A PreToolUse hook (`.claude/hooks/scope-guard.sh`) **blocks** edits outside them. Unrelated findings go to `docs/backlog/`, never fixed in place.
3. **Review** (Opus, `reviewer` agent): checks the diff against the spec, scope, tests and coverage; returns `APPROVE` or `CHANGES_REQUESTED` with numbered items. Max 3 rounds, then handoff to the human with a summary.
4. **Gate:** `./gradlew check` (tests, Kover, ktlint, detekt, ArchUnit/Modulith) must pass; the same command runs in CI.

## 10. Infrastructure and budget (D2: ≤ $200/mo)

| Item | Phase 0–5 (dev) | Public launch (est.) |
|---|---|---|
| Kubernetes | k3d local, $0 | Hetzner k3s, sized from the memory table below: **3 × 8 GB nodes** + volumes; price **TBD** (re-quote when buying, Hetzner list prices changed in 2025–26) |
| Postgres/Kafka/Redis | in-cluster | in-cluster (Strimzi, CloudNativePG), $0 extra |
| Observability | LGTM in-cluster | same, plus optional New Relic free tier |
| Domain + Cloudflare | from Phase 4 (D3) | ~$1/mo + free tier |
| LLM (Claude Haiku) + embeddings (Voyage) | capped $20 | capped **$120**, hard circuit breaker |
| Headroom | | whatever the nodes leave; if infra > $60, shrink the LLM cap rather than D2 |

**Memory estimate at launch (requests, steady state).** The earlier "$40, 3 small nodes" figure was never computed; this is.

| Workload | Replicas | Memory |
|---|---|---|
| core `api` | 2 | 2 × 768 MB |
| core `worker` | 1 | 768 MB |
| identity | 1 | 512 MB |
| Postgres (CloudNativePG, + pgvector HNSW in RAM) | 1 + 1 standby | 2 × 1.5 GB |
| Kafka (Strimzi, KRaft, 1 broker) + operator | 1 | 1.5 GB |
| Redis | 1 | 256 MB |
| LGTM (Loki, Tempo, Mimir, Grafana) + OTel Collector | 1 each | ~3.5 GB |
| MinIO | 1 | 512 MB |
| Platform: ingress, cert-manager, Linkerd, Kyverno, Argo CD, External Secrets | — | ~2.5 GB |
| k3s system + kubelet reserve | 3 nodes | 3 × 600 MB |
| **Total** | | **≈ 16 GB** → 24 GB cluster leaves room for one node failure only by shedding LGTM/Argo CD |

Levers if it doesn't fit: Grafana Cloud free tier instead of in-cluster LGTM (−3.5 GB), Argo CD → Flux (−0.7 GB), single
Postgres instance with WAL archiving instead of a standby. Pricing for LLM and embeddings gets re-verified in the task
that introduces them; the budget is enforced by the cost meter, not by estimates.

## 11. Phases

Each phase ends with a demoable, observable, secured increment. Exit criteria are checked by the reviewer agent.

### Phase 0: Foundations *(done 2026-11-15, CI pending first push)*
- ✅ Gradle multi-module + convention plugins; quality gates (Kover 70% floor, ktlint, detekt, ArchUnit, Modulith verification).
- ✅ Core service skeleton: roles, virtual threads, actuator on separate port, OTel (traces/metrics/logs over OTLP),
  wide-event filter, problem+json, deny-by-default security with hardened headers, Flyway baseline (pgvector).
- ✅ Local dev: Testcontainers-backed `bootTestRun` (Postgres+pgvector); compose file for LGTM observability.
  Redis and Kafka arrive with their first consumer (task 001 and the event-externalization task) so no idle infrastructure is wired.
- ✅ Distroless container image; verified end to end: request → wide event in Loki (structured attributes) + trace in Tempo.
- ✅ Agentic workflow: CLAUDE.md, planner/implementer/reviewer agents, `/plan-task` + `/run-task` skills, scope-guard hook, backlog.
- ✅ CI: build/test/coverage, image build + Trivy, gitleaks, CodeQL, task-scope job; Renovate; SECURITY.md, README.
- 🔧 Hardening from the 2026-11-15 external review (pgvector search path, Flyway/DB roles, wide-event safety,
  problem+json for security errors, forwarded headers, dependency locking, scope-guard fixes, LICENSE): see `docs/tasks/`.
  Progress: 002 (DB wiring + wide-event safety) ✅ done; 003, 004, 005 ready.
- **Exit:** `./gradlew check` green locally ✅ and in CI (after first push); a request produces one wide event plus a trace ✅;
  hardening tasks merged.

> Phases revised 2026-11-15: Kubernetes moves to Phase 1 (D16), identity moves after the anonymous vertical slices
> (D4), and the vibe axes get an offline validation gate before Phase 3 engineering.

### Phase 1: Platform baseline and anonymous access
- k3d cluster + Helm chart for core (`api`/`worker` deployments, probes, resources, security context), CloudNativePG,
  Redis; `make`/script for one-command local cluster. CI renders and lints the chart (`helm lint`, kubeconform).
- DB roles (`app_rw`, `migrator`) with Flyway as a separate Job; jOOQ codegen pipeline.
- Anonymous sessions (signed tokens, TTL, budgets) + rate limiting (task 001, Bucket4j/Redis, cost-weighted).
- Kafka (Strimzi on k3d) + Spring Modulith event externalization (D12).
- Threat model v1, BOLA test harness, ZAP baseline in CI.
- **Exit:** `helm install` on k3d serves `/v1/meta` with a wide event and trace in LGTM; anon session issued and rate-limited.

### Phase 2: Catalog and takes (vertical slice 1)
- Providers: TMDB, IGDB, OpenLibrary, MusicBrainz, AniList; canonical media + dedup; search.
- Takes CRUD for anonymous sessions (user-owned takes arrive with Phase 4).
- **Parallel: vibe-axis validation (gate for Phase 3).** Offline notebook/script: ~150 media across ≥5 kinds, LLM-scored
  on the 16 axes with the draft rubric; measure inter-run stability (same model, repeated), agreement with a small
  hand-labelled set, axis redundancy (correlation/PCA), and whether cross-kind nearest neighbours look sensible to a
  human. Outcome: keep, merge or drop axes; rubric v1 frozen. No production code.
- **Exit:** an anonymous user searches across kinds and records takes, fully traced; vibe-axis report committed under `docs/research/`.

### Phase 3: Vibe engine and matching (vertical slice 2, the core value)
- **Entry gate:** the vibe-axis report recommends a rubric with acceptable stability and agreement.
- Rubric v1 + anchors, catalog prior annotation (batch), take extraction worker, embeddings, pgvector HNSW.
- Quick match (overlap-IDF) → deep match → explanations → partial reveal for anon. The quick match returns picks
  with a refinement status (§6 client contract, spec F01).
- Computed cross-media vibe links; cost meter + circuit breaker.
- Offline eval harness (reference profiles from openly licensed data only, clearly labelled; see risk register).

### Phase 4: Identity and accounts
- Domain + transactional email (D3) set up first.
- `services/identity` (SAS, OIDC, PKCE, Argon2id, email verification, MFA TOTP, refresh rotation); core as resource server.
- Merge-on-signup (anon takes → account), GDPR export/delete.
- Visibility choice after the first sign-in (D20), `GET /v1/me`, user preferences.

### Phase 5: Social, imports, feedback, ops
- Follow, feed, comments (list, edit, delete), likes on takes, blocks/reports, moderation.
- `library` module (saved media with statuses, spec F04) and the Discover deck of user links with save and pass
  reactions (spec F03).
- Imports (Letterboxd, Goodreads, StoryGraph, Trakt, MAL, Steam, ListenBrainz) via Kafka with job status; want-to
  lists become `WANT` library entries.
- Match feedback + experiment assignment; user-proposed vibe links + voting (published at once, D19).
- LLM digests + report export; telemetry MCP server.

### Phase 6: Production hardening and launch
- Argo CD, Linkerd, NetworkPolicies, Kyverno, cosign, External Secrets, Strimzi production config.
- Cloudflare; load tests (k6) with HPA/KEDA; chaos drills; runbooks; backups and restore test.
- **Gate:** ASVS 5.0 L2 checklist complete; SLO dashboards; budget alarms; infra quote fits §10.

## 12. Risk register

| Risk | Mitigation |
|---|---|
| Cold start (few users) | Catalog vibe priors; aspect-level matching; labelled reference profiles; show confidence |
| Popularity bias | IDF weighting; blockbuster damping |
| O(n²) matching | ANN candidates + re-rank; incremental recompute from events |
| LLM cost-DoS | Async only, budgets, cache, circuit breaker |
| Prompt injection | Data-only prompts, schema-constrained output, no tools |
| Sensitive inference from taste data | Public/Anonymous choice at sign-up with a plain disclosure that all data feeds matching (D20), server-side redaction, view quotas, GDPR delete |
| Abuse in user-proposed links (published at once, D19) | Reports and takedown before public launch; trust weighting; client warning dialog for external links |
| Malicious imports | Streaming, caps, no URL fetches, sandboxed worker |
| Sybil/fake profiles | Trust score, anomaly detection, feedback reputation |
| Provider ToS | Attribution; no Spotify-derived ML |
| Seed/eval data licensing | Research datasets (e.g. MovieLens) forbid redistribution and most non-research use: never seed public profiles from them; reference profiles are hand-built or from openly licensed data, and labelled as such |
| Vibe axes don't carry signal | Offline validation gate before Phase 3 (§11); embedding-only recall still works if axes are cut |
| Infra outgrows budget | Memory table in §10; Grafana Cloud free tier as the first lever |
| Kafka delivery semantics | Outbox, idempotency keys, DLQ + replay |
| Scope creep | Phase gates; task specs with non-goals; backlog instead of drive-by fixes |
| Public repo leaks | gitleaks pre-commit + CI, External Secrets, no real config in git |
