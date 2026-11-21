# 011 — Deploy core to the local cluster with in-cluster LGTM, and an exit-criterion smoke test

- **Status:** READY
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-21; amended by claude-opus-5-5 on 2026-11-21 after the spec audit
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

Notation: `<root>` is the repository root (the `root` variable of 010's `env.sh`); `<tag>` is the image tag of §3 step 2
(`local-<hash>`, `<hash>` = its 12 hex characters); `<name>` is a check name from §6; `<reason>` is defined in §6;
`<url>` is a URL of the §6 `port-forward` step; `<token>` is the session token of check 5; `<trace_id>` is the id kept
by check 7; `<now ns>` is the current epoch time in nanoseconds; `<code>` is an HTTP status; `<i>` is a request number
counted from 1; `<value>` is the actual value a check read; `<n>` is a curl exit code. `kube`, `helm_local` and
`show_state` come from 010's `env.sh`.

### 1. LGTM (`deploy/k8s/local/lgtm.yaml`)
- Namespace `observability`, labels `pod-security.kubernetes.io/enforce|warn|audit: restricted`,
  `app.kubernetes.io/part-of: tastebuds`.
- Deployment `lgtm` (1 replica, `strategy: Recreate`, `progressDeadlineSeconds: 1200` (see Limits), labels and selector
  `app.kubernetes.io/name: lgtm`, plus `app.kubernetes.io/part-of: tastebuds`), image `grafana/otel-lgtm:0.41.0` (the
  compose image).
  Pod: `automountServiceAccountToken: false`, `enableServiceLinks: false`, `securityContext`: `runAsNonRoot: true`,
  `runAsUser: 10001`, `runAsGroup: 10001`, `fsGroup: 10001`, `seccompProfile: RuntimeDefault`. Container:
  `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`; three `emptyDir`
  volumes:
  - `/data` (`sizeLimit: 2Gi`): the data of Loki, Prometheus, Pyroscope and Grafana, and Tempo's WAL and blocks
    (`/data/tempo/...` in the image's `tempo-config.yaml`);
  - `/tmp` (`sizeLimit: 64Mi`): the ready file;
  - `/var/tempo` (`sizeLimit: 64Mi`): Tempo's shutdown marker directory (Tempo's default path). Without it Tempo
    exits at start ("Error: Tempo exited before becoming ready") and the pod never becomes Ready.
  Env `GF_AUTH_ANONYMOUS_ENABLED="true"`, `GF_AUTH_ANONYMOUS_ORG_ROLE=Admin` (as in compose; reachable only through
  `kubectl port-forward --address 127.0.0.1`). Ports `grafana` 3000, `otlp-grpc` 4317, `otlp-http` 4318.
  `readinessProbe`: exec `["/otel-lgtm/docker/healthcheck.sh"]`, `initialDelaySeconds: 10`, `periodSeconds: 10`,
  `timeoutSeconds: 5`, `failureThreshold: 30`; no liveness probe. Resources: requests `cpu: 250m, memory: 1Gi`,
  limits `memory: 2Gi`.
- Service `lgtm` (ClusterIP): `otlp-grpc` 4317, `otlp-http` 4318, `grafana` 3000. Loki (3100) and Tempo (3200) are
  reached only by `kubectl port-forward deploy/lgtm` in the smoke test.
- **Pre-decided fallback**, in this order, only as far as needed for the pod to become Ready (narrow fixes first):
  1. Permission errors inside `/otel-lgtm` (the image makes it group-0 writable for arbitrary UIDs): set
     `runAsGroup: 0`, nothing else.
  2. A component writes to a path outside the three mounts, and that path is absent or empty in the image: add an
     `emptyDir` (`sizeLimit: 64Mi`) at that path, nothing else. (This is how `/var/tempo` was found.)
  3. Only when a write goes into a directory that also holds files of the image (an `emptyDir` there would hide them):
     set `readOnlyRootFilesystem: false` for the `lgtm` container only, and add to `trivy.yaml`
     `scan: {skip-files: [deploy/k8s/local/lgtm.yaml]}` with a comment ("third-party, local-only image that writes
     inside its install directory"). Record the written path in a backlog item.
  `trivy.yaml` may change only for fallback 3. Non-fatal Grafana warnings about `plugins-bundled` need no fallback.

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
   `kube -n observability rollout status deployment/lgtm --timeout=1260s` (the first image pull is about 914 MiB).
   On failure: `show_state observability`, then print "LGTM was not ready in 1260 s. If its pod is still pulling the
   image, wait until it is Running (source scripts/k8s/env.sh; kube -n observability get pods), then run
   scripts/k8s/deploy-core.sh again." and exit 1. (While the Deployment reports `ProgressDeadlineExceeded`, a new
   rollout wait may fail at once (UNVERIFIED, see Assumptions verified), so the user waits for the pull first.)
2. **Image**: `"$root/gradlew" -p "$root" :services:core:bootJar`. The jar is the single file matching
   `$root/services/core/build/libs/core-*.jar` (the Dockerfile copies the same glob); with no match print
   "no jar in services/core/build/libs", with several matches print them, and exit 1. Tag = `local-` + the first 12
   hex characters of the SHA-256 of the jar followed by the Dockerfile:
   `cat "$jar" "$root/services/core/Dockerfile" | sha256sum` (or `shasum -a 256` when `sha256sum` is missing, as on
   macOS). Then `docker build -t "tastebuds-core:$tag" "$root/services/core"` and
   `k3d image import "tastebuds-core:$tag" --cluster "$CLUSTER_NAME"`.
   The tag follows the content: unchanged code gives the same jar (Gradle leaves an up-to-date jar untouched, and its
   entries have fixed timestamps), the same tag and no rollout; changed code rolls out. The tag does not come from the
   Docker image ID: with BuildKit and the containerd image store (the default for new Docker 29 installs) the ID covers
   build attestations with timestamps and can change on every build (UNVERIFIED), which would roll out on every run.
   The base images are not part of the tag: a newer base image alone does not roll out (local only).
3. **Signing key**: Secret `tastebuds-anon-session` (Opaque, label `app.kubernetes.io/part-of: tastebuds`), created
   only when missing (`kube -n "$NAMESPACE" get secret tastebuds-anon-session` fails), with
   `TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_ID: "local1"` and `TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_SECRET` =
   `key="$(openssl rand -base64 32)"` (command substitution drops the trailing newline, so the value is one line).
   As in 010 §7 step 5: the manifest (with `stringData`, values double-quoted; Base64 has no `"` or `\`) is written by
   the builtin `printf` into a pipe, `printf '...' "$key" | kube -n "$NAMESPACE" create -f -`. No heredoc (bash 3.2
   writes it to a temporary file) and no `apply` (it keeps a plain-text copy in the `last-applied-configuration`
   annotation). Never printed, never in argv, never on disk.
4. **Release**: `helm_local upgrade --install tastebuds-core "$root/deploy/helm/tastebuds-core" --namespace
   "$NAMESPACE" -f "$root/deploy/k3d/tastebuds-core-values.yaml" --set image.tag="$tag" --wait --timeout 12m`
   (12 min is larger than the migrate Job's 600 s deadline; see Limits). On failure: `show_state "$NAMESPACE"`, then
   print the hints "Migration logs: source scripts/k8s/env.sh; kube -n tastebuds logs job/tastebuds-core-migrate" and
   "If Helm says another operation (install/upgrade/rollback) is in progress, see 'Recover a stuck release' in
   docs/deploy/LOCAL_CLUSTER.md.", and exit 1.
5. Print the image tag and the next steps (`scripts/k8s/smoke.sh`, `scripts/k8s/port-forward.sh`).

### 4. `scripts/k8s/up.sh` (executable)
The one command: runs `scripts/k8s/cluster-up.sh`, then `scripts/k8s/deploy-core.sh`; `set -euo pipefail`; prints
"Local stack ready. Check it: scripts/k8s/smoke.sh".

### 5. `scripts/k8s/port-forward.sh` (executable)
Source `env.sh`; forward `svc/tastebuds-core-api` `18080:80` (namespace `tastebuds`) and `deploy/lgtm` `13000:3000`
(namespace `observability`) in the background, each with `--address 127.0.0.1` (explicit, so the anonymous Grafana
admin is never reachable from the network); print `API: http://127.0.0.1:18080/v1/meta` and
`Grafana: http://127.0.0.1:13000`; `trap ... EXIT INT TERM` kills both; `wait`.
Ports 18080 and 13000 avoid the compose stack's 3000/4317/4318.

### 6. `scripts/k8s/smoke.sh` (executable): the exit criterion
Requires `curl` and `jq` (check up front). Source `env.sh`. Record `start_ns` = epoch seconds × 10^9 (portable; macOS
`date` has no `%N`). Every `curl` call uses `--max-time 10` and never `-f` (the status code is read with
`-w '%{http_code}'`). Port-forwards run in the background, each with `--address 127.0.0.1 --pod-running-timeout=20s`
and its output in a file of a `mktemp -d` directory; a `trap ... EXIT INT TERM` kills them and removes the directory:
- `svc/tastebuds-core-api 18080:80` and `deploy/tastebuds-core-api 18081:8081` (namespace `tastebuds`; 8081 is the
  management port, never in the Service);
- `deploy/tastebuds-core-worker 18082:8081` (namespace `tastebuds`);
- `deploy/lgtm 13100:3100 13200:3200` (namespace `observability`).

Then the steps run in order. Each prints `PASS <name>` or `FAIL <name>: <reason>` on stdout; the first failure exits 1.
Diagnostics (port-forward output) go to stderr. The session token lives only in a shell variable: never printed, never
written to a file, and the session response body is never echoed. No step prints a response body.

`<reason>` is one of:
- `status <code>`: an HTTP answer with an unexpected status (for example `status 503`);
- `curl exit <n>`: no HTTP answer (for example 7, connection refused; 28, no answer within 10 s);
- `<condition> is false`: a `jq` condition of the table that did not hold, written as in the table (for example
  `.status == "UP" is false`);
- a check-specific text given in the table.

The first step, `port-forward`, waits up to 30 s (one round per second) until each URL answers, in this order:
`http://127.0.0.1:18080/v1/meta`, `http://127.0.0.1:18081/actuator/health/liveness`,
`http://127.0.0.1:18082/actuator/health/liveness` (any HTTP status counts as an answer), and
`http://127.0.0.1:13100/ready` (status 200 only). If a URL has not answered after 30 s, it prints
`FAIL port-forward: <url> did not answer in 30 s` for the first such URL in that order, then the output files of all
port-forwards (stderr), and exits 1. Otherwise it prints `PASS port-forward`.

| # | Name | Check | Check-specific reasons |
|---|---|---|---|
| 1 | `migration job succeeded` | `kube -n tastebuds get job tastebuds-core-migrate --ignore-not-found -o name` is not empty; `kube -n tastebuds get job tastebuds-core-migrate -o jsonpath='{.status.succeeded}'` is `1`; and `kube -n tastebuds logs job/tastebuds-core-migrate` has a JSON line (lines starting with `{`) with `.message == "wide_event"`, `.job.name == "migrate"`, `.outcome == "success"` | `job not found`; `succeeded is '<value>'`; `no wide_event line with job.name migrate and outcome success` |
| 2 | `meta is served` | `GET /v1/meta` → 200 with `.service == "tastebuds-core"` and `.role == "API"` | |
| 3 | `runtime role reaches the database and redis` | `GET http://127.0.0.1:18081/actuator/health` → 200 with `.status == "UP"` (the api's root health includes the `db` indicator, which connects as `tastebuds_app`, and `redis`) | |
| 4 | `worker reaches the database` | `GET http://127.0.0.1:18082/actuator/health` → 200 with `.status == "UP"` (the worker's root health includes `db` as `tastebuds_app`; the chart sets `MANAGEMENT_HEALTH_REDIS_ENABLED=false` for the worker; details are never shown) | |
| 5 | `anonymous session is issued` | `POST /v1/sessions/anonymous` → 201 with `.tokenType == "Bearer"` and `.token` starting with `tba1.` | `token does not start with tba1.` (the token itself is never printed) |
| 6 | `the session is rate-limited` | Up to 40 × `GET /v1/meta` with `Authorization: Bearer <token>`: the first non-200 answer is 429, arrives within 25 requests (session capacity 20; greedy refill may add one or two), has a `Retry-After` header and `Content-Type` starting with `application/problem+json` | `status <code> at request <i>`; `no 429 in 40 requests`; `429 at request <i>, expected at most 25`; `no Retry-After header`; `Content-Type '<value>'` |
| 7 | `wide events reach Loki` | `GET http://127.0.0.1:13100/loki/api/v1/query_range` (`-G --data-urlencode`, `start=$start_ns`, `end=<now ns>`, `limit=20`, `direction=backward`), retried every 3 s until 60 s have passed: (a) `{service_name="tastebuds-core"} \|= "wide_event" \| http_route="/v1/meta" \| principal_tier="anon"` has at least one entry; keep its `trace_id` from `[.data.result[].stream.trace_id // empty][0]`; (b) `{service_name="tastebuds-core"} \|= "wide_event" \| rate_limit_limited_by="session"` has at least one entry | `no entry for query (a) in 60 s`; `no trace_id in the entries of query (a)`; `no entry for query (b) in 60 s` |
| 8 | `the request trace is in Tempo` | `GET http://127.0.0.1:13200/api/traces/<trace_id>` → 200, retried every 3 s until 60 s have passed | `status <code> after 60 s`; `curl exit <n> after 60 s` |

A path without a host (`/v1/...`) goes to `http://127.0.0.1:18080`. In the table, `\|` stands for a plain `|` in the
LogQL query (escaped only for Markdown). Loki in this image returns
OTLP log attributes (dots turned into underscores) and `trace_id` in `.data.result[].stream` (see Assumptions
verified), so no other response form is needed.
On success print `Phase 1 exit criterion: PASS` and exit 0.

### 7. Docs
`docs/deploy/LOCAL_CLUSTER.md` (extend task 010's file) with a section "Deploy core and check the exit criterion":
`scripts/k8s/up.sh` (platform plus core), `scripts/k8s/smoke.sh` (what each check proves),
`scripts/k8s/port-forward.sh` (API on 127.0.0.1:18080; Grafana on 127.0.0.1:13000 → Explore → Loki or Tempo, service
`tastebuds-core`); what `deploy-core.sh` does (LGTM, image tagged by content and imported, signing-key Secret,
`helm upgrade --install` with `deploy/k3d/tastebuds-core-values.yaml`); the demo rate limits and why; redeploying after
a code change (run `deploy-core.sh` again; a newer base image alone does not roll out); the approximate memory use per
component (LGTM about 1.1 GiB right after start). Plus these items, with the exact commands (every command after
`source scripts/k8s/env.sh` in bash, from the repository root, because `kube` and `helm_local` are functions of that
file):
- Troubleshooting: migration Job logs (`kube -n tastebuds logs job/tastebuds-core-migrate`); an LGTM wait that timed
  out during the first image pull (§3 step 1).
- "Recover a stuck release": `helm_local -n tastebuds status tastebuds-core` shows the status. `failed`: run
  `deploy-core.sh` again (it upgrades the failed release). `pending-upgrade` (deploy interrupted, for example by
  Ctrl-C): `helm_local -n tastebuds rollback tastebuds-core`, then `deploy-core.sh`. `pending-install` (first install
  interrupted): `helm_local -n tastebuds uninstall tastebuds-core`, then `deploy-core.sh`. Or start over with
  `cluster-down.sh` and `up.sh`.
- `helm_local -n tastebuds uninstall tastebuds-core` keeps Job `tastebuds-core-migrate` (Helm does not track hook
  objects); the next install replaces it, or remove it with `kube -n tastebuds delete job tastebuds-core-migrate`.
- Old images stay: each code change adds `tastebuds-core:local-<hash>` to host Docker and to the cluster node, and no
  script removes old ones. On the host: `docker image ls tastebuds-core`, then `docker image rm tastebuds-core:<tag>`;
  the node's copies go with `cluster-down.sh`.
- LGTM keeps its data only in `emptyDir`: logs, traces and dashboard changes are lost when the pod restarts, when
  `lgtm.yaml` changes (`Recreate`), and with `cluster-down.sh`.

`README.md`:
- Status line: "> Status: **Phase 1, platform baseline and anonymous access.** See the [plan](docs/PLAN.md) for
  architecture, roadmap and decisions."
- Under "Run it", a subsection "Run on Kubernetes (k3d)": requirements (Docker, JDK 21 to build the image), the
  commands `scripts/k8s/up.sh`, `scripts/k8s/smoke.sh`, `scripts/k8s/port-forward.sh`, `scripts/k8s/cluster-down.sh`,
  and a link to `docs/deploy/LOCAL_CLUSTER.md`.
- Repository map, `deploy` row: "Helm chart (`deploy/helm`), local k3d cluster (`deploy/k3d`, `deploy/k8s/local`),
  compose for local observability"; `docs` row: add `[database roles](docs/deploy/DATABASE.md)`.

### 8. Lifecycle of what this task creates
| Object | Create | Re-run | Failure or interrupt | Removal |
|---|---|---|---|---|
| Namespace `observability`, Deployment and Service `lgtm` | `kube apply` | `apply`; a changed manifest restarts the pod (`Recreate`) and its data is lost | wait timeout: diagnostics; the image pull continues in the node | with the cluster |
| Image `tastebuds-core:<tag>` | `docker build`, `k3d image import` | same jar: same tag, imported again, no rollout; new jar: new tag, rollout | a failed build or import stops the script before Helm runs | never by the scripts: host `docker image rm`; node copies with `cluster-down.sh` |
| Secret `tastebuds-anon-session` | `create` when missing | never changed | — | with the cluster; rotation follows docs/security/ANON_SESSIONS.md |
| Helm release `tastebuds-core` | `upgrade --install` | upgrade | `failed`: the next run upgrades it; `pending-*` after an interrupt: "Recover a stuck release" (§7) | `helm_local -n tastebuds uninstall tastebuds-core` or `cluster-down.sh` |
| Job `tastebuds-core-migrate` (Helm hook) | every install and upgrade | `before-hook-creation` deletes the previous Job first | `backoffLimit: 1`, deadline 600 s; the failed Job stays for its logs until the next run | not removed by `helm uninstall`; `kube -n tastebuds delete job tastebuds-core-migrate` |
| Port-forwards and the smoke temp directory | `smoke.sh`, `port-forward.sh` | — | the `EXIT INT TERM` trap kills the forwards and removes the directory | — |

## Assumptions verified
"Audit" means the spec audit of 2026-11-21, which ran or read each item. No k3d cluster was created while planning, so
every acceptance criterion that runs `kube` is UNVERIFIED until the implementer runs it.

| Claim | How checked |
|---|---|
| `grafana/otel-lgtm:0.41.0` becomes healthy with a read-only root filesystem, user 10001:10001, all capabilities dropped, no privilege escalation and writable `/data`, `/tmp` and `/var/tempo`; `/otel-lgtm/docker/healthcheck.sh` exits 0 | Ran with `docker run --read-only --user 10001:10001 --cap-drop ALL --security-opt no-new-privileges` and three tmpfs mounts: healthy at the first probe; about 1.07 GiB memory; no read-only errors in the log (also audit A1) |
| Without `/var/tempo` the stack stops | Ran the same without `/var/tempo`: "Error: Tempo exited before becoming ready", container exit code 1 |
| Tempo writes only its shutdown marker to `/var/tempo`; its WAL and blocks are in `/data/tempo` | Read `/otel-lgtm/tempo-config.yaml` in the image: `/data/tempo/wal`, `/data/tempo/blocks`, `/data/tempo/generator/wal` |
| The check 7 LogQL queries match OTLP log attributes (`http.route` → `http_route`, `principal.tier` → `principal_tier`, `rate_limit.limited_by` → `rate_limit_limited_by`); `trace_id` is in `.stream`; Tempo answers 200 for a known trace and 404 for an unknown one | Ran against the same container: posted an OTLP/HTTP log with body `wide_event` and the app's attribute names plus a span with the same trace id. Query (a): 1 entry, and the spec's `jq` filter returned the sent trace id; query (b): 1 entry; (b) with `ip` instead of `session`: 0 entries; Tempo 200 after about 3 s, unknown id 404. The payload is synthetic, with the shape `OpenTelemetryAppender` produces (message as body, key-value pairs as attributes, trace context) |
| The boot jar has fixed entry timestamps, and build-info has no time | All 278 entries of the current `services/core/build/libs/core-0.1.0-SNAPSHOT.jar` are dated 1980-02-01 00:00; `tastebuds.spring-service` excludes `time` from build-info |
| UNVERIFIED: two `bootJar` runs without code changes give the same jar SHA-256 | Implementer checks first (`./gradlew` was not run while planning); AC 2 depends on it |
| UNVERIFIED: with BuildKit and the containerd image store, the image ID changes between identical builds (attestations) | Not relied on: the tag comes from the jar and the Dockerfile. This machine (Docker 29.1.3, containerd snapshotter, no buildx, legacy builder) gave the same ID twice (audit A5) |
| bash 3.2 writes a heredoc to a temporary file; a pipe from the builtin `printf` stays a pipe | Ran `bash:3.2.57` and `bash:5.2.37` (see 010) |
| `kubectl port-forward` waits up to `--pod-running-timeout` (default 1 min) for a running pod | Audit A3 (kubectl v1.35.10 help) |
| Helm `--timeout` is the "time to wait for any individual Kubernetes operation (like Jobs for hooks)" | Audit A2 (Helm 4.3 `upgrade --help`) |
| The console wide event nests dotted keys (`.job.name`) | docs/observability/WIDE_EVENT_SCHEMA.md: "Dotted keys become nested objects" |
| UNVERIFIED: `kubectl rollout status` stops with "exceeded its progress deadline" once the Deployment reports `ProgressDeadlineExceeded` | Not relied on for correctness: `progressDeadlineSeconds` (1200 s) is smaller than the wait (1260 s), so the wait ends inside its limit either way |
| UNVERIFIED: `k3d image import` imports an image built with the containerd image store | Implementer checks first; if it fails, record the error and stop (HANDOFF), no workaround |
| UNVERIFIED: through `kubectl port-forward`, the api sees every request from the loopback address, so all smoke requests share one IP bucket | Implementer checks with check 6 (the 429 must come from the session bucket, query 7b) |
| UNVERIFIED: Helm 4.3.2 leaves `pending-install` or `pending-upgrade` when killed during the wait; `rollback` recovers `pending-upgrade`, `uninstall` recovers `pending-install`, and `upgrade --install` upgrades a `failed` release | Docs only (§7), no acceptance criterion depends on it |
| UNVERIFIED: the app's OTLP batch export sends logs and spans within a few seconds (SDK defaults) | Checks 7 and 8 retry for 60 s |

## Limits
| Limit | Value | Wrapped by (must be larger) |
|---|---|---|
| LGTM `progressDeadlineSeconds` | 1200 s: the first pull of about 914 MiB needs at least about 7 Mbit/s | LGTM rollout wait, 1260 s |
| LGTM rollout wait | 1260 s | None. When it fires: diagnostics; the image pull continues in the node |
| LGTM readiness probe | 10 s delay, every 10 s, 5 s timeout, 30 failures; no liveness probe, so the pod is never restarted, only unready | LGTM rollout wait |
| LGTM `emptyDir` size limits | `/data` 2Gi, `/tmp` 64Mi, `/var/tempo` 64Mi; above a limit the kubelet evicts the pod (data lost) | — |
| LGTM memory | request 1Gi, limit 2Gi; about 1.07 GiB measured right after start | Docker memory of the node (about 6 GB for the stack, 010 docs) |
| Helm `--timeout` | 12 min = 720 s for each operation: the hook Job wait, then the resource wait | None. When it fires, the release is `failed`; the Job and pods keep running |
| Migrate Job `activeDeadlineSeconds` | 600 s (chart default) | Helm `--timeout`, 720 s |
| Migrate Job retries | `backoffLimit: 1`, so at most 2 pods; each: Flyway `connectRetries` 6 (backoff 1+2+4+8+16+32 = 63 s) plus at most 7 connect attempts × pgjdbc `connectTimeout` 10 s ≈ 133 s; about 270 s plus JVM starts in total | Job deadline, 600 s |
| What keeps running after the Job deadline | Kubernetes kills the pod, but a Postgres backend that waits for a lock keeps waiting (no `lock_timeout` on the migration connection yet; `docs/backlog/2026-11-21-migration-lock-wait-unbounded.md`). In the local cluster no other client holds long locks | — |
| api and worker `startupProbe` | 36 × 5 s = 180 s | Deployment `progressDeadlineSeconds` (600 s default), inside Helm `--timeout` (720 s) |
| `tastebuds_app` `connectionLimit` (010) | 40 | Peak pools during a rollout: (api 1 + surge 1 + worker 1 + surge 1) × `database.poolSize` 10 = 40, at the limit. A second api replica in the local values needs a higher limit |
| Port-forward `--pod-running-timeout` | 20 s | `port-forward` step, 30 s |
| `port-forward` step | 30 s, one round per second | None |
| curl, each request | `--max-time 10` | The 60 s window of checks 7 and 8, or none |
| Check 6 burst | the first 25 requests must take less than 15 s: greedy refill adds one token per 3 s to the 20-token session bucket | — |
| Checks 7 and 8 | retry every 3 s until 60 s have passed; the last attempt can add up to 10 s | None |

## Acceptance criteria
1. On a machine with Docker, JDK 21, git, curl, openssl and jq and no existing cluster, `scripts/k8s/up.sh` exits 0,
   then `scripts/k8s/smoke.sh` exits 0, printing `PASS port-forward`, `PASS` for all eight checks and
   `Phase 1 exit criterion: PASS` (the Phase 1 exit criterion).
2. Running `scripts/k8s/deploy-core.sh` again without code changes keeps the image tag and rolls neither Deployment:
   `metadata.generation` of `tastebuds-core-api` and of `tastebuds-core-worker` is unchanged.
3. `scripts/k8s/smoke.sh` names the failing step and the reason. Two negative runs, each followed by a restore and a
   passing run (`smoke.sh` exits 0):
   a. `kube -n tastebuds delete job tastebuds-core-migrate`, then `smoke.sh` exits 1, and its stdout is exactly the
      two lines `PASS port-forward` and `FAIL migration job succeeded: job not found`. Restore:
      `scripts/k8s/deploy-core.sh` (the pre-upgrade hook creates the Job again).
   b. `kube -n tastebuds scale deployment/tastebuds-core-worker --replicas=0`, then `smoke.sh` exits 1, its stdout is
      exactly the line `FAIL port-forward: http://127.0.0.1:18082/actuator/health/liveness did not answer in 30 s`,
      and its stderr has the port-forward output files. Restore:
      `kube -n tastebuds scale deployment/tastebuds-core-worker --replicas=1` and
      `kube -n tastebuds rollout status deployment/tastebuds-core-worker --timeout=240s`.
   A Redis outage is not used as a negative: the api's Redis health indicator reuses a shared connection whose
   behaviour during reconnects planning could not run, so the reason (status 503 or curl exit 28) would not be fixed.
4. Neither script output nor the repository contains the session token or any Secret value; the signing key is created
   through a `printf` pipe into `kube create -f -` only, and
   `kube -n tastebuds get secret tastebuds-anon-session -o yaml | grep -c last-applied-configuration` prints `0`.
5. The LGTM pod is admitted under `enforce: restricted` in `observability` and becomes Ready with the security context
   and the three `emptyDir` mounts of §1, or a fallback step of §1 is applied as written (with the backlog item for
   step 3).
6. `scripts/k8s/lint.sh` passes: kubeconform covers `lgtm.yaml`; Trivy reports no HIGH/CRITICAL finding (only fallback
   3 may skip `lgtm.yaml`).
7. Every kubectl/helm call of the new scripts goes through `kube`/`helm_local`: task 010's AC 3a `grep` still prints
   exactly the two `env.sh` wrapper definitions.
8. Docs and README match §7.

## Required tests
No automated test framework exists for these scripts; the checks below are required evidence in the handoff
(command plus the relevant output lines):
- `scripts/k8s/lint.sh` (shellcheck covers the four new scripts; kubeconform and Trivy cover `lgtm.yaml`).
- `scripts/k8s/up.sh` from a deleted cluster, then `scripts/k8s/smoke.sh` with `PASS port-forward`, its eight `PASS`
  lines and the final line (AC 1).
- The second `deploy-core.sh` run with the image tag and both Deployment generations before and after (AC 2).
- Both negative smoke runs with their exit codes and FAIL lines, each restore, and the passing run after each (AC 3).
- The AC 4 `grep -c` output (`0`) and the AC 7 `grep` output.
- `kube -n observability get pod -l app.kubernetes.io/name=lgtm -o jsonpath='{.items[0].spec.containers[0].securityContext}'`
  and `kube -n observability get pod -l app.kubernetes.io/name=lgtm -o jsonpath='{.items[0].spec.containers[0].volumeMounts}'` (AC 5).
- Before AC 2: two `./gradlew :services:core:bootJar` runs with `sha256sum services/core/build/libs/core-*.jar` after
  each (equal hashes; the UNVERIFIED jar claim).

## Quality gates
- `./gradlew check` passes (no Kotlin change expected).
- `scripts/k8s/lint.sh` passes locally and in CI.
- No new build dependency; images only as pinned tags.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
