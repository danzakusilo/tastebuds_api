# tastebuds-core Helm chart

Deploys the core service (the `api` and `worker` roles of one image) and runs the `migrate` command as a Helm
pre-install/pre-upgrade Job.

| Resource | Purpose |
|---|---|
| Deployment `<release>-api` | HTTP API (role `api`), port 8080, actuator on 8081 |
| Deployment `<release>-worker` | Background work (role `worker`); rendered when `worker.enabled` |
| Service `<release>-api` | ClusterIP in front of the api pods only (actuator port is never exposed) |
| Job `<release>-migrate` | Runs Flyway as the schema owner before pods roll (hook, last run is kept until the next upgrade) |
| ServiceAccount `<release>` | Used by api and worker; no token is mounted, no RBAC objects |

In this README, `<release>` means the chart fullname. That is `fullnameOverride` if set; otherwise the release name
when it contains `tastebuds-core`; otherwise `<release name>-tastebuds-core`.

## Requirements

- Kubernetes 1.30 or newer (`lifecycle.preStop.sleep`).
- Postgres prepared as described in [docs/deploy/DATABASE.md](../../../docs/deploy/DATABASE.md).
- Redis (used by the api for rate limiting).
- An OTLP/HTTP endpoint.
- Ingress is not part of the chart.

## Secrets

The chart never creates Secrets. Create these before install:

| Value key | Type | Keys | Read by |
|---|---|---|---|
| `database.appSecret` | `kubernetes.io/basic-auth` | `username`, `password` | api and worker |
| `database.migratorSecret` | `kubernetes.io/basic-auth` | `username`, `password` | migration Job only |
| `anonSession.existingSecret` | `Opaque` | env names `TASTEBUDS_ANONSESSION_SIGNINGKEYS_<n>_ID` / `_SECRET` | api only |

## Values

Required values have no default; `helm template` fails with `<key> is required` when one is missing.

| Key | Default | Meaning |
|---|---|---|
| `nameOverride`, `fullnameOverride` | `""` | Standard Helm name overrides |
| `image.repository` | `tastebuds-core` | Image repository |
| `image.tag` | `""` | Image tag; the chart `appVersion` when empty |
| `image.pullPolicy` | `IfNotPresent` | Image pull policy |
| `environment` | required | `TASTEBUDS_ENV`, for example `local`, `staging`, `prod` |
| `database.url` | required | JDBC URL |
| `database.appSecret` | required | Secret of the runtime login role |
| `database.migratorSecret` | required | Secret of the schema owner |
| `database.poolSize` | `10` | Connection pool size |
| `redis.host` | required | Redis host (api only) |
| `redis.port` | `6379` | Redis port |
| `anonSession.existingSecret` | required | Secret with the signing keys (api only) |
| `otel.endpoint` | required | OTLP/HTTP base URL |
| `extraEnv` | `[]` | Extra non-secret env vars for api and worker, list of `{name, value}` |
| `api.replicas`, `api.resources` | `2`, 768Mi memory | Api Deployment |
| `worker.enabled`, `worker.replicas`, `worker.resources` | `true`, `1`, 768Mi memory | Worker Deployment |
| `migration.connectRetries` | `6` | Flyway connection attempts (backoff 1 s, 2 s, 4 s, ...) |
| `migration.activeDeadlineSeconds` | `600` | Job deadline |
| `migration.resources` | 512Mi memory | Job resources |
| `service.port` | `80` | Service port |
| `terminationGracePeriodSeconds` | `45` | Pod termination grace period |

There are no CPU limits on purpose (throttling); the memory limit equals the request.

## Upgrades

The migration Job runs before the new pods roll. During the rollout the previous release keeps serving against the
new schema, so every migration must be backward compatible; see
[docs/deploy/DATABASE.md](../../../docs/deploy/DATABASE.md).

## Rotating a Secret

Edit the Secret, then run `kubectl rollout restart deployment/<release>-api` (and `-worker` for the database Secret).
Signing keys follow [docs/security/ANON_SESSIONS.md](../../../docs/security/ANON_SESSIONS.md).

## Pod security

Every pod (api, worker, migration Job) meets the `restricted` Pod Security Standard: non-root user 65532, read-only
root filesystem (`/tmp` is an `emptyDir`), all capabilities dropped, no privilege escalation, `RuntimeDefault`
seccomp, no service-account token, no service links. Only the migration Job receives the schema owner's credentials.

## Checks

Run `scripts/k8s/lint.sh` from the repository root. It needs Docker, git and jq, and runs shellcheck, `helm lint`,
a required-values check, kubeconform, the chart unit tests (`tests/`) and a Trivy misconfiguration scan with pinned
tool images. CI runs the same script. `ci/ci-values.yaml` holds the values used by these checks.
