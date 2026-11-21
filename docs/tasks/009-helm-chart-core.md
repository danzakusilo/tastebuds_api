# 009 — Helm chart for core: api and worker Deployments, migration Job, restricted pod security, chart checks in CI

- **Status:** READY
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-21
- **Depends on:** 008

## Goal
docs/PLAN.md D16 and §11 Phase 1: "Helm chart for core (`api`/`worker` deployments, probes, resources, security
context) … CI renders and lints the chart (`helm lint`, kubeconform)". The chart is the deployment contract from day
one: it deploys the api and worker roles of one image, runs the `migrate` command (task 008) as a pre-install/
pre-upgrade Job with the schema owner's credentials, and takes Postgres, Redis, the OTLP endpoint and every Secret
from the environment. All pods meet the `restricted` Pod Security Standard. One script, `scripts/k8s/lint.sh`, runs
every static check for `deploy/` (shellcheck, `helm lint`, kubeconform, chart unit tests, Trivy misconfiguration scan)
with pinned tools in Docker, identically on a laptop and in CI. Task 010 creates the local cluster and task 011
installs this chart there.

## Non-goals
- Ingress or Gateway API, TLS, NetworkPolicies, PodDisruptionBudget, HPA, topology spread, Linkerd (Phase 6; the
  ingress controller is not chosen yet).
- `values.schema.json` (add once the values settle), `nodeSelector`/`tolerations`/`affinity`/`podAnnotations`/
  `imagePullSecrets` values (add when a target needs them).
- Creating Secrets in the chart, or any secret value in values files. Publishing the chart or images, image signing.
- CloudNativePG, Redis, LGTM manifests and cluster scripts (010, 011). A CI job that boots a cluster.
- Application code or `application.yaml` changes. OTLP export from the migration Job (task 008 §4).
- Narrowing `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` (needs the ingress; possible today through `extraEnv`).
- Renovate rules for the image tags pinned in `scripts/k8s/lint.sh`; pinning GitHub Actions
  (`docs/backlog/2026-11-15-pin-github-actions.md`).

## Allowed paths
- `deploy/helm/tastebuds-core/**`
- `scripts/k8s/lint.sh`
- `trivy.yaml`
- `.github/workflows/ci.yml`
- `SECURITY.md`

## Design notes

### 1. Layout
```
deploy/helm/tastebuds-core/
  Chart.yaml  values.yaml  README.md  .helmignore
  ci/ci-values.yaml                 # values for lint, kubeconform, unit tests and Trivy (names only, no secrets)
  templates/_helpers.tpl
  templates/serviceaccount.yaml
  templates/deployment-api.yaml
  templates/deployment-worker.yaml
  templates/service.yaml
  templates/job-migrate.yaml
  tests/*_test.yaml                 # helm-unittest suites (§8)
```
`Chart.yaml`: `apiVersion: v2`, `name: tastebuds-core`, `type: application`, `version: 0.1.0`,
`appVersion: "0.1.0-SNAPSHOT"` (the Gradle project version), a one-line `description`,
`home: https://github.com/danzakusilo/tastebuds_api`. No `kubeVersion` field (state "Kubernetes ≥ 1.30" in the README:
`preStop.sleep`). No NOTES.txt. `.helmignore`: standard VCS/editor patterns plus `tests/`, `ci/`.

### 2. `values.yaml` (exactly these keys and defaults; comments as shown, shortened if needed)
```yaml
# tastebuds-core: the core service (api + worker roles of one image) and its migration Job (pre-install/pre-upgrade).
# Postgres, Redis, the OTLP endpoint and every Secret come from the environment, see README.md. Never put secrets here.
nameOverride: ""
fullnameOverride: ""

image:
  repository: tastebuds-core
  tag: ""                  # default: the chart's appVersion
  pullPolicy: IfNotPresent

environment: ""            # required: TASTEBUDS_ENV (deployment.environment in telemetry), e.g. local, staging, prod

database:
  url: ""                  # required: JDBC URL, e.g. jdbc:postgresql://tastebuds-db-rw:5432/tastebuds
  appSecret: ""            # required: kubernetes.io/basic-auth Secret (username, password) of the runtime login role (member of app_rw)
  migratorSecret: ""       # required: kubernetes.io/basic-auth Secret of the schema owner; only the migration Job reads it
  poolSize: 10

redis:
  host: ""                 # required (api only: rate limiting)
  port: 6379

anonSession:
  existingSecret: ""       # required: Secret whose keys are the env names TASTEBUDS_ANONSESSION_SIGNINGKEYS_<n>_ID/_SECRET (api only)

otel:
  endpoint: ""             # required: OTLP/HTTP base URL; the app appends /v1/traces, /v1/logs, /v1/metrics

extraEnv: []               # extra non-secret env vars for api and worker: list of {name, value}

api:
  replicas: 2
  resources:
    requests: {cpu: 250m, memory: 768Mi}
    limits: {memory: 768Mi}

worker:
  enabled: true
  replicas: 1
  resources:
    requests: {cpu: 250m, memory: 768Mi}
    limits: {memory: 768Mi}

migration:
  connectRetries: 6        # Flyway connection attempts while the database is not reachable yet (backoff 1 s, 2 s, 4 s, ...)
  activeDeadlineSeconds: 600
  resources:
    requests: {cpu: 250m, memory: 512Mi}
    limits: {memory: 512Mi}

service:
  port: 80

terminationGracePeriodSeconds: 45
```
No CPU limits on purpose (throttling); memory limit = request. Memory figures follow PLAN §10.

`ci/ci-values.yaml`:
```yaml
# Values for chart CI: helm lint, kubeconform, helm-unittest and Trivy. Names only, never secrets.
image:
  tag: ci
environment: ci
database:
  url: jdbc:postgresql://ci-db-rw:5432/tastebuds
  appSecret: ci-db-app
  migratorSecret: ci-db-migrator
redis:
  host: ci-redis
anonSession:
  existingSecret: ci-anon-session
otel:
  endpoint: http://ci-otel:4318
```

### 3. Helpers (`_helpers.tpl`)
- `tastebuds-core.name`, `tastebuds-core.fullname`, `tastebuds-core.chart`: the standard `helm create` logic
  (truncate 63, `nameOverride`, `fullnameOverride`, release name containing the chart name).
- `tastebuds-core.labels` (takes a dict `ctx`, `component`): `helm.sh/chart`, `app.kubernetes.io/name`,
  `app.kubernetes.io/instance`, `app.kubernetes.io/version` (appVersion), `app.kubernetes.io/managed-by`,
  `app.kubernetes.io/part-of: tastebuds`, `app.kubernetes.io/component: <component>`.
- `tastebuds-core.selectorLabels` (same dict): `app.kubernetes.io/name`, `app.kubernetes.io/instance`,
  `app.kubernetes.io/component`.
- `tastebuds-core.image`: `<repository>:<tag | default .Chart.AppVersion>`.
- `tastebuds-core.podSecurityContext` and `tastebuds-core.containerSecurityContext`: §5, fixed, not configurable.
- Required values: every template reads each required value it uses as
  `required "<key> is required" .Values.<key>`, with the key path in the message, e.g. `database.url is required`.

Resource names: ServiceAccount `<fullname>`, Deployments `<fullname>-api` and `<fullname>-worker`, Service
`<fullname>-api`, Job `<fullname>-migrate`. With release name `tastebuds-core` the fullname is `tastebuds-core`.

### 4. Workloads
**Container ports** (api and worker): `http` 8080, `management` 8081 (actuator; never in the Service).

**Environment, api** (`deployment-api.yaml`), in this order:

| Name | Value |
|---|---|
| `TASTEBUDS_ROLE` | `api` |
| `TASTEBUDS_ENV` | `environment` |
| `DATABASE_URL` | `database.url` |
| `DATABASE_USERNAME` | `secretKeyRef` `database.appSecret` / `username` |
| `DATABASE_PASSWORD` | `secretKeyRef` `database.appSecret` / `password` |
| `DATABASE_POOL_SIZE` | `database.poolSize` (quoted) |
| `SPRING_FLYWAY_ENABLED` | `"false"` |
| `REDIS_HOST` | `redis.host` |
| `REDIS_PORT` | `redis.port` (quoted) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `otel.endpoint` |
| `extraEnv` entries | appended |

plus `envFrom: [{secretRef: {name: <anonSession.existingSecret>}}]` (rotation per docs/security/ANON_SESSIONS.md
means editing that Secret, then `kubectl rollout restart`).

**Environment, worker** (`deployment-worker.yaml`, rendered only when `worker.enabled`): the api table **without**
`REDIS_HOST`, `REDIS_PORT` and without `envFrom` (the worker has no signing keys and no Redis use), with
`TASTEBUDS_ROLE=worker`, plus `MANAGEMENT_HEALTH_REDIS_ENABLED="false"` (Redis is unused in the worker, so the root
health must not report it; see `docs/backlog/2026-11-20-redis-health-contributor-all-roles.md`).

**Probes** (api and worker, on port `management`):

| Probe | Path | period | timeout | failureThreshold |
|---|---|---|---|---|
| `startupProbe` | `/actuator/health/liveness` | 5 s | 2 s | 36 (3 min to start) |
| `livenessProbe` | `/actuator/health/liveness` | 10 s | 2 s | 3 |
| `readinessProbe` | `/actuator/health/readiness` | 5 s | 2 s | 3 |

Never the root `/actuator/health` (it includes Redis on the api, which fails open by design).
`lifecycle.preStop.sleep.seconds: 5` (endpoints deregister before Tomcat's graceful shutdown);
`terminationGracePeriodSeconds` from values.

**Deployments**: `replicas` from values, `revisionHistoryLimit: 3`, strategy `RollingUpdate` with `maxSurge: 1`,
`maxUnavailable: 0`, `serviceAccountName: <fullname>`, `resources` from values, `image`/`imagePullPolicy` from values.

**Service** (`service.yaml`): `<fullname>-api`, `ClusterIP`, port `service.port` named `http` → `targetPort: http`,
selector = selectorLabels with component `api`.

**ServiceAccount** (`serviceaccount.yaml`): `<fullname>`, `automountServiceAccountToken: false`. No RBAC objects.

**Migration Job** (`job-migrate.yaml`):
- Annotations: `helm.sh/hook: pre-install,pre-upgrade`, `helm.sh/hook-weight: "0"`,
  `helm.sh/hook-delete-policy: before-hook-creation` (the finished Job and its logs stay until the next upgrade).
- `backoffLimit: 1`, `activeDeadlineSeconds: migration.activeDeadlineSeconds`, pod `restartPolicy: Never`.
- No `serviceAccountName`: pre-install hooks run before the chart's ServiceAccount exists, so the pod uses the
  namespace default account, with `automountServiceAccountToken: false`.
- Container `migrate`: same image, `args: ["migrate"]`, `resources: migration.resources`, no ports, no probes.
- Environment, exactly: `TASTEBUDS_ENV`, `DATABASE_URL`, `FLYWAY_USERNAME` and `FLYWAY_PASSWORD` (`secretKeyRef`
  `database.migratorSecret` / `username`, `password`), `SPRING_FLYWAY_CONNECT_RETRIES` (`migration.connectRetries`,
  quoted). Nothing else: no app credentials, no Redis, no signing keys, no `extraEnv`, no `envFrom`.
- Only this template may reference `database.migratorSecret`.

### 5. Pod security (every pod: api, worker, migrate)
Pod: `automountServiceAccountToken: false`, `enableServiceLinks: false` (otherwise a Service named `redis` injects
`REDIS_PORT=tcp://…`, which breaks the app's `${REDIS_PORT}` placeholder), `securityContext`:
`runAsNonRoot: true`, `runAsUser: 65532`, `runAsGroup: 65532`, `fsGroup: 65532` (distroless `nonroot`),
`seccompProfile: {type: RuntimeDefault}`.
Container `securityContext`: `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`,
`capabilities: {drop: [ALL]}`. Volume `tmp` (`emptyDir: {sizeLimit: 64Mi}`) mounted at `/tmp` (Tomcat work dir).

### 6. `scripts/k8s/lint.sh` (new, executable, `set -euo pipefail`, shellcheck-clean)
Header comment: "Static checks for Kubernetes artifacts under deploy/. Needs only Docker; tool versions are pinned here
and used the same way locally and in CI." Runs from the repository root (`git rev-parse --show-toplevel`), prints a
heading per step, stops at the first failure. Pinned images (constants at the top):
```bash
SHELLCHECK_IMAGE=koalaman/shellcheck:v0.11.0
HELM_IMAGE=helmunittest/helm-unittest:4.3.0-1.2.1   # Helm 4.3.0 + helm-unittest 1.2.1 (entrypoint: helm unittest)
KUBECONFORM_IMAGE=ghcr.io/yannh/kubeconform:v0.8.0
TRIVY_IMAGE=aquasec/trivy:0.76.2
KUBERNETES_VERSION=1.34.12                          # newest schemas published for kubeconform
CHART=deploy/helm/tastebuds-core
CI_VALUES=$CHART/ci/ci-values.yaml
```
Every container mounts the repository read-only (`-v "$root:/work:ro" -w /work`); Helm also gets `-e HOME=/tmp`
(the image's plugin lives in `HELM_DATA_HOME=/usr/local/share/helm`, so `HOME` does not matter for it). Steps:
1. **shellcheck**: `scripts/k8s/*.sh`.
2. **helm lint**: `--entrypoint helm` … `lint --strict $CHART -f $CI_VALUES`.
3. **required values**: `helm template $CHART` with chart defaults must **fail** (assert non-zero exit).
4. **kubeconform**: `helm template tastebuds-core $CHART -f $CI_VALUES` piped into
   `kubeconform -strict -summary -kubernetes-version $KUBERNETES_VERSION -` (`docker run -i`).
5. **helm-unittest**: `helm unittest $CHART` (the image's entrypoint).
6. **Trivy misconfiguration** (same scanner and severity as the CI `dependencies` job, with the chart rendered via
   `trivy.yaml`): `trivy fs --scanners misconfig --severity HIGH,CRITICAL --format json --quiet
   --skip-dirs '**/build' --skip-dirs .gradle --skip-dirs .tools --skip-dirs .kotlin .` into a temp file on the host
   (`mktemp`, removed by `trap`), then with `jq` on the host:
   - the chart was rendered and scanned: at least one `.Results[].Target` matches `templates/deployment-api\.yaml$`;
   - no failure: `[.Results[]?.Misconfigurations[]? | select(.Status == "FAIL")]` is empty; otherwise print
     `ID Severity Title Target` per failure and exit 1.
   `jq` is the only host tool besides Docker and git; check for it up front with a clear error.

### 7. `trivy.yaml` (new, repository root; read by every `trivy` run from the root: the CI Trivy steps and step 6)
```yaml
# Trivy configuration, read from the repository root by CI and scripts/k8s/lint.sh.
misconfiguration:
  helm:
    # The chart does not render with its defaults (required values are empty); scan it with the CI values.
    values:
      - deploy/helm/tastebuds-core/ci/ci-values.yaml
```
If Trivy resolves the path differently, fix the path here until lint.sh step 6 finds the rendered templates; CI scans
`.` from the root too, so the same path then works there.

### 8. Chart unit tests (helm-unittest, `deploy/helm/tastebuds-core/tests/`)
Every suite loads `values: [../ci/ci-values.yaml]` unless a test sets otherwise. Use JSONPath filters for negative
checks, e.g. `notExists: {path: "spec.template.spec.containers[0].env[?(@.valueFrom.secretKeyRef.name == 'ci-db-migrator')]"}`.
Suites and test names are in "Required tests".

### 9. CI (`.github/workflows/ci.yml`)
New job after `agentic-scripts`:
```yaml
  k8s:
    name: kubernetes manifests
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v7
      - run: scripts/k8s/lint.sh
```
No other change to the workflow.

### 10. Chart `README.md`
Concise: what the chart deploys (table: resource, purpose); requirements (Kubernetes ≥ 1.30, Postgres prepared per
docs/deploy/DATABASE.md, Redis, an OTLP/HTTP endpoint); the three Secrets (table: value key, type, keys, read by:
`database.appSecret` basic-auth → api and worker; `database.migratorSecret` basic-auth → migration Job only;
`anonSession.existingSecret` Opaque with env-name keys → api only); the values table (key, default, meaning); the
upgrade order (the Job migrates before pods roll; migrations must be backward compatible, see DATABASE.md); rotating
a Secret (edit, then `kubectl rollout restart deployment/<release>-api`; signing keys follow ANON_SESSIONS.md); pod
security summary; how to run `scripts/k8s/lint.sh`.

### 11. `SECURITY.md`
Insert after the bullet "Distroless non-root container image; …":
"- Helm chart: every pod runs with the restricted Pod Security profile (non-root, read-only root filesystem, all
capabilities dropped, `RuntimeDefault` seccomp, no service-account token, no service links); only the migration Job
receives the schema owner's credentials, see [deploy/helm/tastebuds-core/README.md](deploy/helm/tastebuds-core/README.md)"

### 12. Backlog
Edit `docs/backlog/2026-11-20-redis-health-contributor-all-roles.md`: probes use the health groups and the worker
disables the Redis contributor (this task). Keep the item open only for the remaining part: set
`spring.data.redis.timeout` before sessions or caches use the Boot Redis beans. Update its title and **What**
accordingly.

## Acceptance criteria
1. `scripts/k8s/lint.sh` exits 0 on a machine with only Docker, git and jq, running steps 1–6 of §6 in order; it
   exits non-zero when any step fails (verify once by temporarily breaking a template locally; do not commit that).
2. `helm template` with the chart defaults fails; each of the seven required values (`environment`, `database.url`,
   `database.appSecret`, `database.migratorSecret`, `redis.host`, `anonSession.existingSecret`, `otel.endpoint`)
   produces the message `<key> is required` when it is the only one missing (unit tests).
3. The rendered api Deployment has exactly the env of §4 (api table) and the `envFrom` of the anon-session Secret;
   the worker Deployment has the worker env, no `envFrom`, no `REDIS_*`; neither references `database.migratorSecret`
   nor sets `FLYWAY_*`.
4. The migration Job is a `pre-install,pre-upgrade` hook with delete policy `before-hook-creation`, runs `args:
   ["migrate"]` with `restartPolicy: Never`, `backoffLimit: 1` and the configured deadline, and its env is exactly the
   five variables of §4; `grep -rln migratorSecret deploy/helm/tastebuds-core/templates` lists only `job-migrate.yaml`.
5. Every pod spec (api, worker, Job) has the pod and container security settings of §5, `/tmp` on an `emptyDir`, and
   images `<repository>:<tag>` defaulting to `tastebuds-core:0.1.0-SNAPSHOT`.
6. Probes match the table in §4 and target the `management` port; the Service selects only `component: api` and
   targets port `http`.
7. kubeconform (strict, Kubernetes 1.34.12 schemas) accepts the rendered chart; Trivy reports no HIGH/CRITICAL
   misconfiguration for the rendered chart and proves it rendered it (§6 step 6).
8. CI has the `k8s` job of §9; no other workflow change.
9. Chart README, `SECURITY.md` bullet and the backlog edit match §10–§12; no secret value appears anywhere under
   `deploy/`.

## Required tests
helm-unittest suites in `deploy/helm/tastebuds-core/tests/`:
- `deployment_api_test.yaml`:
  - `runs the api role and never migrates` (`TASTEBUDS_ROLE=api`, `SPRING_FLYWAY_ENABLED="false"`)
  - `reads database credentials from the app secret only` (both `secretKeyRef`s to `ci-db-app`; negative: no env entry
    references `ci-db-migrator`, no `FLYWAY_USERNAME`/`FLYWAY_PASSWORD`)
  - `loads the signing keys from the anon-session secret` (`envFrom` contains `secretRef.name: ci-anon-session`)
  - `configures redis, the pool and the otlp endpoint` (`REDIS_HOST`, `REDIS_PORT="6379"`, `DATABASE_POOL_SIZE="10"`,
    `OTEL_EXPORTER_OTLP_ENDPOINT`, `TASTEBUDS_ENV=ci`)
  - `probes the health groups on the management port` (all three probes per §4; container ports `http` 8080,
    `management` 8081; `preStop.sleep.seconds: 5`)
  - `runs with the restricted pod security profile` (every field of §5)
  - `uses the chart app version when no image tag is set` (`image.tag: ""` → `tastebuds-core:0.1.0-SNAPSHOT`)
  - `appends extra environment variables` (`extraEnv` entry present)
  - `rolls out one pod at a time without losing capacity` (replicas 2, `maxSurge: 1`, `maxUnavailable: 0`)
- `deployment_worker_test.yaml`:
  - `runs the worker role and never migrates`
  - `gets no signing keys and no redis` (negative: no `envFrom`, no `REDIS_HOST`/`REDIS_PORT`;
    `MANAGEMENT_HEALTH_REDIS_ENABLED="false"`)
  - `never receives the migrator credentials` (negative, as for the api)
  - `runs with the restricted pod security profile`
  - `is not rendered when the worker is disabled` (`worker.enabled: false` → `hasDocuments: {count: 0}`)
- `job_migrate_test.yaml`:
  - `is a pre-install and pre-upgrade hook that keeps its last run`
  - `runs the migrate command once` (`args`, `restartPolicy`, `backoffLimit`, `activeDeadlineSeconds: 600`)
  - `uses only the migrator credentials` (`FLYWAY_*` from `ci-db-migrator`; negative: no `DATABASE_USERNAME`/
    `DATABASE_PASSWORD`, no `envFrom`, no `REDIS_HOST`, env has exactly 5 entries)
  - `waits for the database with connect retries` (`SPRING_FLYWAY_CONNECT_RETRIES="6"`)
  - `runs with the restricted pod security profile without a service account token` (§5; no `serviceAccountName`)
- `service_test.yaml`: `exposes only the api pods on port 80`
- `serviceaccount_test.yaml`: `never mounts a token`
- `required_values_test.yaml`: one test per required value, `fails without <key>` (`failedTemplate` with
  `errorPattern: "<key> is required"`), against the template that reads it first (`job-migrate.yaml` for
  `database.migratorSecret`, `deployment-api.yaml` for the others).

## Quality gates
- `./gradlew check` passes (no Kotlin change expected).
- `scripts/k8s/lint.sh` passes locally and in the new CI job.
- No new build dependency. New tools only as pinned Docker images in `scripts/k8s/lint.sh` (justified: Helm, chart unit
  tests, schema validation and the same Trivy scan as CI, runnable with Docker alone).

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
