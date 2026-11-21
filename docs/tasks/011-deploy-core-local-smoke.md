# 011 — Deploy core to the local cluster with in-cluster LGTM, and an exit-criterion smoke test

- **Status:** READY
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-21
- **Depends on:** 009, 010

## Goal
docs/PLAN.md §11 Phase 1 exit criterion: "`helm install` on k3d serves `/v1/meta` with a wide event and trace in LGTM;
anon session issued and rate-limited." Task 010 builds the platform (k3d, CloudNativePG with roles, Redis); task 009
provides the chart. This task adds the observability backend to the cluster (Grafana LGTM, as in PLAN §10 "LGTM
in-cluster"), builds the core image and installs the chart with local values (`scripts/k8s/deploy-core.sh`), makes
`scripts/k8s/up.sh` the one command for the whole local stack, and adds `scripts/k8s/smoke.sh`, which checks every part
of the exit criterion against the running cluster and exits 0 only when all of them hold.

## Non-goals
- A CI job that boots k3d and runs the smoke test (natural first step of the Phase 1 "ZAP baseline in CI" task, which
  needs an ephemeral environment).
- Ingress, TLS, NetworkPolicies, persistent LGTM storage, an OTel Collector in front of LGTM (Phase 6).
- Any change to the chart (009), the platform manifests or scripts of 010 (`cluster-up.sh`, `env.sh`, `tools.sh`,
  `lint.sh`, `postgres.yaml`, `redis.yaml`, `namespaces.yaml`), application code or `application.yaml`.
- Changing the default rate limits: the demo limits live only in the local values file (§2).
- Measuring Lettuce log volume during a Redis outage (`docs/backlog/2026-11-20-lettuce-reconnect-log-volume.md`).
- `CLAUDE.md` (the human adds the new commands there if wanted).

## Allowed paths
- `deploy/k8s/local/lgtm.yaml`
- `deploy/k3d/tastebuds-core-values.yaml`
- `scripts/k8s/deploy-core.sh`
- `scripts/k8s/up.sh`
- `scripts/k8s/port-forward.sh`
- `scripts/k8s/smoke.sh`
- `docs/deploy/LOCAL_CLUSTER.md`
- `README.md`
- `trivy.yaml`

## Design notes

### 1. LGTM (`deploy/k8s/local/lgtm.yaml`)
- Namespace `observability`, labels `pod-security.kubernetes.io/enforce|warn|audit: restricted`,
  `app.kubernetes.io/part-of: tastebuds`.
- Deployment `lgtm` (1 replica, `strategy: Recreate`, labels and selector `app.kubernetes.io/name: lgtm`, plus
  `app.kubernetes.io/part-of: tastebuds`), image `grafana/otel-lgtm:0.41.0` (the compose image).
  Pod: `automountServiceAccountToken: false`, `enableServiceLinks: false`, `securityContext`: `runAsNonRoot: true`,
  `runAsUser: 10001`, `runAsGroup: 10001`, `fsGroup: 10001`, `seccompProfile: RuntimeDefault`. Container:
  `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`; `emptyDir` volumes at
  `/data` (`sizeLimit: 2Gi`; all component data lives there) and `/tmp` (`sizeLimit: 64Mi`; the ready file).
  Env `GF_AUTH_ANONYMOUS_ENABLED="true"`, `GF_AUTH_ANONYMOUS_ORG_ROLE=Admin` (as in compose; reachable only through
  `kubectl port-forward` on 127.0.0.1). Ports `grafana` 3000, `otlp-grpc` 4317, `otlp-http` 4318.
  `readinessProbe`: exec `["/otel-lgtm/docker/healthcheck.sh"]`, `initialDelaySeconds: 10`, `periodSeconds: 10`,
  `timeoutSeconds: 5`, `failureThreshold: 30`; no liveness probe. Resources: requests `cpu: 250m, memory: 1Gi`,
  limits `memory: 2Gi`.
- Service `lgtm` (ClusterIP): `otlp-grpc` 4317, `otlp-http` 4318, `grafana` 3000. Loki (3100) and Tempo (3200) are
  reached only by `kubectl port-forward deploy/lgtm` in the smoke test.
- **Pre-decided fallback**, in this order, only as far as needed for the pod to become Ready:
  1. Permission errors inside `/otel-lgtm` (the image makes it group-0 writable for arbitrary UIDs): set
     `runAsGroup: 0`, nothing else.
  2. Writes outside `/data` and `/tmp`: set `readOnlyRootFilesystem: false` for the `lgtm` container only, and add to
     `trivy.yaml` `scan: {skip-files: [deploy/k8s/local/lgtm.yaml]}` with a comment ("third-party, local-only image
     that writes inside its install directory"). Record the written path in a backlog item.
  `trivy.yaml` may change only for fallback 2.

### 2. Local chart values (`deploy/k3d/tastebuds-core-values.yaml`)
```yaml
# Values for the local k3d cluster (scripts/k8s/deploy-core.sh). No secrets: the scripts create the Secrets.
environment: local
image:
  repository: tastebuds-core
  pullPolicy: Never          # imported with `k3d image import`; deploy-core.sh sets image.tag
database:
  url: jdbc:postgresql://tastebuds-db-rw.tastebuds.svc:5432/tastebuds
  appSecret: tastebuds-db-app
  migratorSecret: tastebuds-db-migrator
redis:
  host: redis.tastebuds.svc
anonSession:
  existingSecret: tastebuds-anon-session
otel:
  endpoint: http://lgtm.observability.svc:4318
api:
  replicas: 1
extraEnv:
  # Demo limits for scripts/k8s/smoke.sh: every port-forwarded request shares the loopback IP bucket, so the IP bucket
  # is large and the session bucket small, and the session bucket is the one that rejects. Defaults: application.yaml.
  - name: TASTEBUDS_RATELIMIT_CAPACITY
    value: "600"
  - name: TASTEBUDS_RATELIMIT_REFILLTOKENS
    value: "600"
  - name: TASTEBUDS_RATELIMIT_SESSION_CAPACITY
    value: "20"
  - name: TASTEBUDS_RATELIMIT_SESSION_REFILLTOKENS
    value: "20"
```
(The largest rule cost, 10 for `POST /v1/sessions/anonymous`, stays below both capacities, as `RateLimitProperties`
requires.)

### 3. `scripts/k8s/deploy-core.sh` (executable)
`set -euo pipefail`; source `scripts/k8s/env.sh`; `require_docker`; if `kube get namespace "$NAMESPACE"` fails,
print "Run scripts/k8s/cluster-up.sh first." and exit 1. Then:
1. **LGTM**: `kube apply -f deploy/k8s/local/lgtm.yaml`;
   `kube -n observability rollout status deployment/lgtm --timeout=600s` (the first image pull is large).
2. **Image**: `"$root/gradlew" -p "$root" :services:core:bootJar`; `docker build -t tastebuds-core:build
   "$root/services/core"`; tag = `local-` + the first 12 hex characters of `docker image inspect --format '{{.Id}}'`
   (after `sha256:`). `docker tag` it and `k3d image import "tastebuds-core:$tag" --cluster "$CLUSTER_NAME"`. The tag
   follows the content: unchanged code gives the same tag and no rollout; changed code rolls out.
3. **Signing key**: Secret `tastebuds-anon-session` (Opaque, label `app.kubernetes.io/part-of: tastebuds`), created
   only when missing, through stdin (`kube apply -f -` with a heredoc and `stringData`), with
   `TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_ID: local1` and `TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_SECRET` =
   `openssl rand -base64 32`. Never printed, never in argv.
4. **Release**: `helm_local upgrade --install tastebuds-core "$root/deploy/helm/tastebuds-core" --namespace
   "$NAMESPACE" -f "$root/deploy/k3d/tastebuds-core-values.yaml" --set image.tag="$tag" --wait --timeout 10m`. On
   failure print `kube -n tastebuds logs job/tastebuds-core-migrate` and `kube -n tastebuds get pods` hints and exit 1.
5. Print the image tag and the next steps (`scripts/k8s/smoke.sh`, `scripts/k8s/port-forward.sh`).

### 4. `scripts/k8s/up.sh` (executable)
The one command: runs `scripts/k8s/cluster-up.sh`, then `scripts/k8s/deploy-core.sh`; `set -euo pipefail`; prints
"Local stack ready. Check it: scripts/k8s/smoke.sh".

### 5. `scripts/k8s/port-forward.sh` (executable)
Source `env.sh`; forward `svc/tastebuds-core-api` `18080:80` (namespace `tastebuds`) and `deploy/lgtm` `13000:3000`
(namespace `observability`) in the background (kubectl binds 127.0.0.1 by default); print
`API: http://localhost:18080/v1/meta` and `Grafana: http://localhost:13000`; `trap` kills both on exit; `wait`.
Ports 18080 and 13000 avoid the compose stack's 3000/4317/4318.

### 6. `scripts/k8s/smoke.sh` (executable): the exit criterion
Requires `curl` and `jq` (check up front). Source `env.sh`. Record `start_ns` = epoch seconds × 10^9 (portable; macOS
`date` has no `%N`). Port-forward `svc/tastebuds-core-api 18080:80` and `deploy/lgtm 13100:3100 13200:3200`
(namespace `observability`) and `deploy/tastebuds-core-api 18081:8081` (the management port, never in the Service)
in the background, output into a `mktemp -d` directory, cleaned up and killed by `trap`.
Wait up to 30 s for `http://localhost:18080/v1/meta` and `http://localhost:18081/actuator/health/liveness` to
answer and for `http://localhost:13100/ready` to return 200.
Then run the checks in order. Each prints `PASS <name>` or `FAIL <name>: <reason>`; the first failure exits 1. The
session token lives only in a shell variable: never printed, never written to a file, and the session response body is
never echoed (print only its status code on failure).

| # | Name | Check |
|---|---|---|
| 1 | `migration job succeeded` | `kube -n tastebuds get job tastebuds-core-migrate -o jsonpath='{.status.succeeded}'` is `1`, and `kube -n tastebuds logs job/tastebuds-core-migrate` has a JSON line (lines starting with `{`) with `.message == "wide_event"`, `.job.name == "migrate"`, `.outcome == "success"` |
| 2 | `meta is served` | `GET /v1/meta` → 200 with `.service == "tastebuds-core"` and `.role == "API"` |
| 3 | `runtime role reaches the database and redis` | `GET http://localhost:18081/actuator/health` → 200 with `.status == "UP"` (the root health includes the `db` indicator, which connects as `tastebuds_app`, and `redis`) |
| 4 | `anonymous session is issued` | `POST /v1/sessions/anonymous` → 201 with `.tokenType == "Bearer"` and `.token` starting with `tba1.` |
| 5 | `the session is rate-limited` | Up to 40 × `GET /v1/meta` with `Authorization: Bearer <token>`: the first non-200 answer is 429, arrives within 25 requests (session capacity 20; greedy refill may add one or two), has a `Retry-After` header and `Content-Type` starting with `application/problem+json` |
| 6 | `wide events reach Loki` | `GET http://localhost:13100/loki/api/v1/query_range` (`-G --data-urlencode`, `start=$start_ns`, `end=<now ns>`, `limit=20`, `direction=backward`), retried every 3 s for up to 60 s: (a) `{service_name="tastebuds-core"} \|= "wide_event" \| http_route="/v1/meta" \| principal_tier="anon"` has at least one entry; keep its `trace_id` from `[.data.result[].stream.trace_id // empty][0]`; (b) `{service_name="tastebuds-core"} \|= "wide_event" \| rate_limit_limited_by="session"` has at least one entry |
| 7 | `the request trace is in Tempo` | `GET http://localhost:13200/api/traces/<trace_id>` → 200, retried every 3 s for up to 60 s |

In the table, `\|` stands for a plain `|` in the LogQL query (escaped only for Markdown).

If (6a) finds entries but no `trace_id` in `.stream`, request again with header
`X-Loki-Response-Encoding-Flags: categorize-labels` and read `.data.result[].values[][2].structuredMetadata.trace_id`;
use whichever form the running Loki returns, and keep only that one in the final script.
On success print `Phase 1 exit criterion: PASS` and exit 0.

### 7. Docs
`docs/deploy/LOCAL_CLUSTER.md` (extend task 010's file) with a section "Deploy core and check the exit criterion":
`scripts/k8s/up.sh` (platform plus core), `scripts/k8s/smoke.sh` (what each check proves),
`scripts/k8s/port-forward.sh` (API on localhost:18080; Grafana on localhost:13000 → Explore → Loki or Tempo, service
`tastebuds-core`); what `deploy-core.sh` does (LGTM, image tagged by content and imported, signing-key Secret,
`helm upgrade --install` with `deploy/k3d/tastebuds-core-values.yaml`); the demo rate limits and why; redeploying after
a code change (run `deploy-core.sh` again); troubleshooting (migration Job logs; a failed first install:
`helm uninstall` through the wrappers, or `cluster-down.sh` and `up.sh`); the approximate memory use per component.

`README.md`:
- Status line: "> Status: **Phase 1, platform baseline and anonymous access.** See the [plan](docs/PLAN.md) for
  architecture, roadmap and decisions."
- Under "Run it", a subsection "Run on Kubernetes (k3d)": requirements (Docker, JDK 21 to build the image), the
  commands `scripts/k8s/up.sh`, `scripts/k8s/smoke.sh`, `scripts/k8s/port-forward.sh`, `scripts/k8s/cluster-down.sh`,
  and a link to `docs/deploy/LOCAL_CLUSTER.md`.
- Repository map, `deploy` row: "Helm chart (`deploy/helm`), local k3d cluster (`deploy/k3d`, `deploy/k8s/local`),
  compose for local observability"; `docs` row: add `[database roles](docs/deploy/DATABASE.md)`.

## Acceptance criteria
1. On a machine with Docker, JDK 21, git, curl, openssl and jq and no existing cluster, `scripts/k8s/up.sh` exits 0,
   then `scripts/k8s/smoke.sh` exits 0 with all seven checks `PASS` (the Phase 1 exit criterion).
2. Running `scripts/k8s/deploy-core.sh` again without code changes keeps the image tag and does not roll the api
   Deployment (`metadata.generation` unchanged).
3. `scripts/k8s/smoke.sh` exits 1 and names the failing check when a check fails (negative: scale
   `deployment/tastebuds-core-api` to 0, run it, expect `FAIL meta is served`; scale back to 1).
4. Neither script output nor the repository contains the session token or any Secret value; the signing key is created
   through stdin only.
5. The LGTM pod is admitted under `enforce: restricted` in `observability` and becomes Ready with the security context
   of §1, or a fallback step of §1 is applied as written (with the backlog item for step 2).
6. `scripts/k8s/lint.sh` passes: kubeconform covers `lgtm.yaml`; Trivy reports no HIGH/CRITICAL finding (only fallback
   2 may skip `lgtm.yaml`).
7. Docs and README match §7.

## Required tests
No automated test framework exists for these scripts; the checks below are required evidence in the handoff
(command plus the relevant output lines):
- `scripts/k8s/lint.sh` (shellcheck covers the four new scripts; kubeconform and Trivy cover `lgtm.yaml`).
- `scripts/k8s/up.sh` from a deleted cluster, then `scripts/k8s/smoke.sh` with its seven `PASS` lines (AC 1).
- The second `deploy-core.sh` run with the api Deployment generation before and after (AC 2).
- The negative smoke run (AC 3).
- `kube -n observability get pod -l app.kubernetes.io/name=lgtm -o jsonpath='{.items[0].spec.containers[0].securityContext}'` (AC 5).

## Quality gates
- `./gradlew check` passes (no Kotlin change expected).
- `scripts/k8s/lint.sh` passes locally and in CI.
- No new build dependency; images only as pinned tags.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
