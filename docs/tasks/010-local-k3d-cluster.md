# 010 — Local k3d cluster with CloudNativePG (database roles, pgvector) and Redis

- **Status:** READY
- **Phase:** 1
- **Planned by:** claude-opus-5-5 on 2026-11-21
- **Depends on:** 008, 009

## Goal
docs/PLAN.md §11 Phase 1: "k3d cluster + … CloudNativePG, Redis; `make`/script for one-command local cluster" and
"DB roles (`app_rw`, `migrator`)". This task builds the platform the core chart (task 009) needs, in a local k3d
cluster: a CloudNativePG Postgres 17 with pgvector whose database is owned by the non-superuser `migrator`, the
`app_rw` group role and the `tastebuds_app` login role (the contract in docs/deploy/DATABASE.md, task 008), and Redis.
`scripts/k8s/cluster-up.sh` creates or resumes everything idempotently and verifies the roles; the only requirement is
Docker, because pinned k3d, kubectl and Helm binaries are downloaded into `.tools/` with SHA-256 verification. No
script ever reads or changes the user's kubeconfig, current context or Helm configuration. This closes
`docs/backlog/2026-11-15-app-rw-role-provisioning.md`. Task 011 then deploys core and LGTM and checks the Phase 1 exit
criterion.

## Non-goals
- Deploying core, LGTM or a smoke test (011). Building images.
- Ingress controller (Traefik is disabled), TLS, NetworkPolicies, backups, more than one Postgres instance, PgBouncer,
  `sslmode=verify-full` for the app's database connection (Phase 6).
- Redis authentication or persistence (local only; rate-limit buckets are ephemeral and the limiter fails open).
- Kafka/Strimzi (Phase 1 item "Kafka + event externalization", a later task).
- A CI job that boots a cluster. Windows support (Linux and macOS only).
- Any change to the chart, application code, `application.yaml` or `trivy.yaml`.
- `make`: plain scripts are the entry points (PLAN allows "`make`/script").

## Allowed paths
- `deploy/k3d/cluster.yaml`
- `deploy/k8s/local/namespaces.yaml`
- `deploy/k8s/local/postgres.yaml`
- `deploy/k8s/local/redis.yaml`
- `scripts/k8s/env.sh`
- `scripts/k8s/tools.sh`
- `scripts/k8s/cluster-up.sh`
- `scripts/k8s/cluster-down.sh`
- `scripts/k8s/lint.sh`
- `.gitignore`
- `docs/deploy/LOCAL_CLUSTER.md`
- `docs/deploy/DATABASE.md`

## Design notes

### 1. Pinned tools (`scripts/k8s/tools.sh`, executable)
Installs missing pinned tools into `<repo>/.tools/bin` and prints that directory as its **only** stdout line
(progress goes to stderr). Bash 3.2 compatible (macOS): no associative arrays, no `${x,,}`, no `mapfile`.
- Platform: `uname -s` → `linux` | `darwin`; `uname -m` → `amd64` (`x86_64`, `amd64`) | `arm64` (`aarch64`,
  `arm64`). Anything else: error and exit 1.
- A tool is current when `.tools/bin/<tool>` exists and `.tools/bin/.<tool>.version` contains the pinned version.
  Otherwise: download into a `mktemp -d` directory under `.tools/`, verify the SHA-256 (`sha256sum`, or
  `shasum -a 256` on macOS), then `chmod 755` and `mv` into place, then write the stamp. On a mismatch: print expected
  and actual hash, delete the download, exit 1. Never install an unverified file.
- `curl -fsSL --proto '=https' --tlsv1.2 --retry 3`.
- Checksums come from this table (from the projects' official checksum files, 2026-11-21); look them up with a
  `case "$tool-$os-$arch"` function:

| Tool | Version | URL | linux-amd64 | linux-arm64 | darwin-amd64 | darwin-arm64 |
|---|---|---|---|---|---|---|
| `helm` | `v4.3.2` | `https://get.helm.sh/helm-v4.3.2-<os>-<arch>.tar.gz` (binary at `<os>-<arch>/helm`; the hash is of the archive) | `d1c0278d46beab79582d7e50f5b3dc206944ba8e577cde618513fd07daa53983` | `df31cb61680ef728f82c770e5280dd565617bee6b039d37e85c677288be4bbb0` | `9e0068e9ae18c441d27388190ee7f53ae65eba44bfe910b40bdac44c4b51fa71` | `6f3421de3a8cc2ac578c8cfb33b8edde290dde2871bc1bab12e24f2e5caeaa90` |
| `kubectl` | `v1.35.10` | `https://dl.k8s.io/release/v1.35.10/bin/<os>/<arch>/kubectl` | `c49a28f1f4efc4d475ed05f47f2ce8e563bf7bef69ed77fe1b924ea6d83b6327` | `283f1be6a46b3695b625e25030106b8ffe18bd0d3a900f5b65c259ca407f04f5` | `4787e20d9029871845d8a57bf56f533870f5d98d9cf3b82b6f5e2ad5eaeee3a3` | `77ec066f3404edb81760865c9cd812d03c25e9a89aa0349fd3c1aa60bd5fb068` |
| `k3d` | `v5.9.0` | `https://github.com/k3d-io/k3d/releases/download/v5.9.0/k3d-<os>-<arch>` | `06d8f25bc3a971c4eb29e0ff08429b180402db0f4dec838c9eac427e296800a0` | `03cde5cf23e6e8e67de5a039ecf26e5b85aca82fba3e5d13dadf904cd218a250` | `b4aabc37534f95b9c764e7823f2df923f50d57600837aa60a06266cce47db732` | `fe106541d5d0a3f18debcd4d432a16f8c0ce3e6ddc06f8fbb6f696a122313e00` |

### 2. Shared settings (`scripts/k8s/env.sh`, sourced, not executable)
First line `# shellcheck shell=bash`; a header comment: "Source it, never execute it. Pins every tool and every
kubectl/helm call to the local cluster: the user's kubeconfig, current context and Helm configuration are never read
or changed." Defines:
- `root` (`git rev-parse --show-toplevel`), `CLUSTER_NAME=tastebuds`, `KUBE_CONTEXT=k3d-tastebuds`,
  `NAMESPACE=tastebuds`.
- `export KUBECONFIG="$root/.tools/kubeconfig"`; `export HELM_CONFIG_HOME="$root/.tools/helm/config"`,
  `HELM_CACHE_HOME="$root/.tools/helm/cache"`, `HELM_DATA_HOME="$root/.tools/helm/data"`.
- `PATH="$("$root/scripts/k8s/tools.sh"):$PATH"` (pinned tools first, so a different system version never runs).
- `kube() { kubectl --context "$KUBE_CONTEXT" "$@"; }` and `helm_local() { helm --kube-context "$KUBE_CONTEXT" "$@"; }`:
  scripts call only these, never bare `kubectl`/`helm` against a cluster.
- `retry <attempts> <delay-seconds> <command...>`: runs the command until it succeeds, sleeping between attempts;
  returns the last exit code.
- `require_docker`: `docker info` must succeed, else "Docker is not running" and exit 1.

### 3. k3d cluster (`deploy/k3d/cluster.yaml`)
```yaml
# Local development cluster (docs/deploy/LOCAL_CLUSTER.md), created by scripts/k8s/cluster-up.sh.
apiVersion: k3d.io/v1alpha5
kind: Simple
metadata:
  name: tastebuds
servers: 1
agents: 0
image: rancher/k3s:v1.35.10-k3s1
kubeAPI:
  hostIP: "127.0.0.1"   # never on all interfaces
  hostPort: "6550"
options:
  k3d:
    wait: true
    timeout: "180s"
  k3s:
    extraArgs:
      - arg: --disable=traefik   # no ingress in Phase 1; services are reached with kubectl port-forward
        nodeFilters:
          - server:*
  kubeconfig:
    updateDefaultKubeconfig: false   # the scripts use .tools/kubeconfig only
    switchCurrentContext: false
```

### 4. Namespace (`deploy/k8s/local/namespaces.yaml`)
Namespace `tastebuds` with labels `pod-security.kubernetes.io/enforce: restricted`,
`pod-security.kubernetes.io/warn: restricted`, `pod-security.kubernetes.io/audit: restricted`,
`app.kubernetes.io/part-of: tastebuds`. Enforcing `restricted` proves the chart's and Redis' security contexts in a
real API server.
**Pre-decided fallback:** if CloudNativePG pods are rejected (`kube -n tastebuds get events` shows
`violates PodSecurity "restricted:latest"`), set only `enforce` to `baseline`, keep `warn` and `audit` at
`restricted`, and record the violating CNPG field in a new backlog item. Do not loosen anything else.

### 5. Postgres (`deploy/k8s/local/postgres.yaml`, namespace `tastebuds`)
CloudNativePG operator: Helm chart `cloudnative-pg` version `0.29.2` (operator 1.30.2) from
`https://cloudnative-pg.github.io/charts`, release `cnpg`, namespace `cnpg-system`.
```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: tastebuds-db
  namespace: tastebuds
spec:
  instances: 1
  imageName: ghcr.io/cloudnative-pg/postgresql:17.12-standard-trixie   # "standard" images include pgvector
  enableSuperuserAccess: false
  storage:
    size: 2Gi
  resources:
    requests: {cpu: 100m, memory: 512Mi}
    limits: {memory: 1Gi}
  bootstrap:
    initdb:
      database: tastebuds
      owner: migrator                     # schema owner, not superuser (docs/deploy/DATABASE.md)
      secret:
        name: tastebuds-db-migrator       # basic-auth, created by cluster-up.sh
      postInitApplicationSQL:
        # pgvector is untrusted: only the superuser may create it, once, before the first migration.
        - CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public
---
apiVersion: postgresql.cnpg.io/v1
kind: DatabaseRole
metadata:
  name: app-rw
  namespace: tastebuds
spec:
  cluster:
    name: tastebuds-db
  name: app_rw
  comment: Runtime data access in schema core, granted by migration V2. Group role, no login.
  login: false
  databaseRoleReclaimPolicy: retain
---
apiVersion: postgresql.cnpg.io/v1
kind: DatabaseRole
metadata:
  name: tastebuds-app
  namespace: tastebuds
spec:
  cluster:
    name: tastebuds-db
  name: tastebuds_app
  comment: Login role of the api and worker pods.
  login: true
  inRoles:
    - app_rw
  connectionLimit: 40
  passwordSecret:
    name: tastebuds-db-app                # basic-auth, created by cluster-up.sh
  databaseRoleReclaimPolicy: retain
```
`DatabaseRole` is CNPG's recommended standalone role resource (1.30). The operator retries `tastebuds_app` until
`app_rw` exists; cluster-up waits for both to report `status.applied: true` before it returns, so the migration Job
(V2 grants to `app_rw`) never races the roles.

### 6. Redis (`deploy/k8s/local/redis.yaml`, namespace `tastebuds`)
Deployment `redis` (1 replica) and Service `redis` (ClusterIP, port 6379 → `redis`), labels
`app.kubernetes.io/name: redis`, `app.kubernetes.io/part-of: tastebuds`.
- Image `redis:8.10.3-alpine` (the image the tests use). Args: `redis-server --save "" --appendonly no
  --maxmemory 64mb --maxmemory-policy allkeys-lru` (as a YAML list). The official image disables protected mode at
  build time, and as non-root its entrypoint execs `redis-server` directly.
- Pod: `automountServiceAccountToken: false`, `enableServiceLinks: false`, `securityContext`: `runAsNonRoot: true`,
  `runAsUser: 999`, `runAsGroup: 1000`, `fsGroup: 1000` (the image's `redis` user), `seccompProfile: RuntimeDefault`.
- Container: `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`;
  `emptyDir` at `/data`; port `redis` 6379; `readinessProbe` exec `["redis-cli", "ping"]` (period 5 s);
  `livenessProbe` `tcpSocket: redis` (period 10 s); resources requests `cpu: 50m, memory: 96Mi`, limits
  `memory: 128Mi`.

### 7. `scripts/k8s/cluster-up.sh` (executable)
`set -euo pipefail`; source `env.sh`; `require_docker`; then, each step printing one line to stderr:
1. **Cluster**: if `k3d cluster get "$CLUSTER_NAME"` succeeds, `k3d cluster start "$CLUSTER_NAME"` (no-op when
   running); otherwise `k3d cluster create --config "$root/deploy/k3d/cluster.yaml"`.
2. **Kubeconfig**: `mkdir -p "$root/.tools"`; with `umask 077`, `k3d kubeconfig get "$CLUSTER_NAME"` into
   `$KUBECONFIG.tmp`, then `mv` to `$KUBECONFIG`. Then `kube wait --for=condition=Ready node --all --timeout=180s`.
3. **Operator**: `helm_local upgrade --install cnpg cloudnative-pg --repo https://cloudnative-pg.github.io/charts
   --version 0.29.2 --namespace cnpg-system --create-namespace --wait --timeout 5m` (`--repo`: no `helm repo add`).
4. **Namespace**: `kube apply -f deploy/k8s/local/namespaces.yaml`.
5. **Secrets**, created only when missing (`kube -n "$NAMESPACE" get secret <name>` fails), never rotated by the
   script: `tastebuds-db-migrator` (username `migrator`) and `tastebuds-db-app` (username `tastebuds_app`), both
   `type: kubernetes.io/basic-auth`, labels `cnpg.io/reload: "true"` and `app.kubernetes.io/part-of: tastebuds`,
   password `openssl rand -hex 32`. The manifest goes to `kube apply -f -` through a heredoc on **stdin** with
   `stringData`: secrets never appear in argv, in output or on disk.
6. **Postgres**: `retry 12 5 kube apply -f deploy/k8s/local/postgres.yaml` (the CNPG webhook may lag the operator's
   readiness by a few seconds).
7. **Redis**: `kube apply -f deploy/k8s/local/redis.yaml`.
8. **Wait**: `kube -n "$NAMESPACE" wait --for=condition=Ready cluster/tastebuds-db --timeout=600s`;
   `kube -n "$NAMESPACE" wait --for=jsonpath='{.status.applied}'=true databaserole/app-rw databaserole/tastebuds-app
   --timeout=300s`; `kube -n "$NAMESPACE" rollout status deployment/redis --timeout=180s`.
9. **Verify the database contract**: find the primary pod with
   `-l cnpg.io/cluster=tastebuds-db,cnpg.io/instanceRole=primary`, then run each query with
   `kube -n "$NAMESPACE" exec <pod> -c postgres -- psql -d tastebuds -tAc "<sql>"` and compare to the expected
   output; on a mismatch print the check name, expected and actual, and exit 1:

| Check | SQL | Expected |
|---|---|---|
| database owner | `select pg_get_userbyid(datdba) from pg_database where datname = 'tastebuds'` | `migrator` |
| migrator | `select concat(rolsuper::text, ',', rolcanlogin::text) from pg_roles where rolname = 'migrator'` | `false,true` |
| app_rw | `select rolcanlogin::text from pg_roles where rolname = 'app_rw'` | `false` |
| app login role | `select pg_has_role('tastebuds_app', 'app_rw', 'member')::text` | `true` |
| pgvector | `select count(*) from pg_extension where extname = 'vector'` | `1` |

10. Print: "Platform ready: Postgres tastebuds-db and Redis in namespace tastebuds." and the two lines that give the
    user kubectl access (`export KUBECONFIG=<root>/.tools/kubeconfig`, `export PATH=<root>/.tools/bin:$PATH`).

Re-running the script on a ready cluster changes nothing and exits 0.

### 8. `scripts/k8s/cluster-down.sh` (executable)
Source `env.sh`; if `k3d cluster get "$CLUSTER_NAME"` succeeds, `k3d cluster delete "$CLUSTER_NAME"`; remove
`$KUBECONFIG`. Keeps `.tools/bin` and the Helm cache. Exit 0 also when no cluster exists.

### 9. `scripts/k8s/lint.sh` (extend task 009's script)
Add a step directly after the chart kubeconform step: validate `deploy/k8s/local/` (the directory, so later files are
covered) with the same image and flags plus
`-schema-location default -schema-location "$CRD_SCHEMAS"`, where
```bash
# CRD schemas (CloudNativePG Cluster, DatabaseRole), pinned to a CRDs-catalog commit of 2026-11-01.
CRD_SCHEMAS='https://raw.githubusercontent.com/datreeio/CRDs-catalog/61bcda00d70c1f40e6f4a5e51b5c159aac932860/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
```
`deploy/k3d/` is not a Kubernetes manifest directory and is not validated. The existing shellcheck step covers the new
scripts (`env.sh` through its `shell=bash` directive) and the Trivy step covers `redis.yaml`.

### 10. `.gitignore`
Append:
```
### Local Kubernetes tools, Helm cache and kubeconfig (scripts/k8s) ###
/.tools/
```

### 11. Docs
`docs/deploy/LOCAL_CLUSTER.md` (new), concise:
- Requirements: Docker with about 6 GB of free memory (for the full stack of task 011), git, curl, openssl. Tools are
  pinned and checksum-verified into `.tools/`.
- Commands: `scripts/k8s/cluster-up.sh`, `scripts/k8s/cluster-down.sh`.
- What it creates (table: component, namespace, details): k3d cluster `tastebuds` (k3s v1.35.10, API on
  127.0.0.1:6550, no Traefik); CloudNativePG 1.30.2 (`cnpg-system`); Postgres `tastebuds-db` (17.12 + pgvector, one
  instance, database `tastebuds` owned by `migrator`); roles `app_rw` and `tastebuds_app`; Redis 8.10 (no auth, no
  persistence); namespace `tastebuds` enforces the restricted Pod Security Standard.
- Secrets: generated once, random, never printed or committed; names and keys.
- Using kubectl and psql yourself (the two exports; `kubectl -n tastebuds exec -it tastebuds-db-1 -c postgres --
  psql tastebuds`). The scripts never touch `~/.kube/config` or your Helm configuration.
- Troubleshooting: port 6550 in use, Docker memory, start over with cluster-down then cluster-up.
- Local-only shortcuts that production must not copy: Redis without auth, one Postgres instance, no TLS verification.

`docs/deploy/DATABASE.md`: in "Where each environment provisions them", replace the local k3d text with a link to
`deploy/k8s/local/postgres.yaml` and `LOCAL_CLUSTER.md` (CloudNativePG `initdb` owner + `postInitApplicationSQL` for
pgvector + two `DatabaseRole`s).

### 12. Backlog
Delete `docs/backlog/2026-11-15-app-rw-role-provisioning.md`: task 008 documented the roles and environment variables
and runs Flyway as a separate process with `migrator`; this task provisions the roles in the local cluster.

## Acceptance criteria
1. On a machine with only Docker, git, curl and openssl, `scripts/k8s/cluster-up.sh` exits 0 and its verification
   step (§7 step 9) passes; a second run exits 0, keeps the same cluster, and leaves both Secrets unchanged (same
   `metadata.resourceVersion` before and after).
2. `tools.sh` installs exactly the pinned versions and verifies every download against the table in §1; with a wrong
   checksum it exits 1 and installs nothing (verify once locally by editing a checksum; do not commit that); its stdout
   is the bin directory only.
3. No script reads or writes `~/.kube/config` or the user's Helm configuration: every kubectl/helm call goes through
   `kube`/`helm_local` with `KUBECONFIG=<repo>/.tools/kubeconfig` (`grep -nE '\b(kubectl|helm) ' scripts/k8s/*.sh`
   shows only the two wrapper definitions and `tools.sh`).
4. Secrets are created through stdin only; no password appears in the repository, in script output or in argv.
5. All pods in namespace `tastebuds` are admitted under `enforce: restricted`, or the §4 fallback is applied and a
   backlog item names the violating field.
6. `kube -n tastebuds exec deploy/redis -- redis-cli ping` prints `PONG`; the Redis pod runs as uid 999 with a
   read-only root filesystem.
7. `scripts/k8s/lint.sh` passes, including kubeconform on `deploy/k8s/local/` with the CNPG CRD schemas and Trivy with
   no HIGH/CRITICAL finding for `redis.yaml`.
8. `scripts/k8s/cluster-down.sh` deletes the cluster and the kubeconfig and exits 0 when run again.
9. Docs match §11; `.gitignore` ignores `/.tools/`; the backlog item is deleted.

## Required tests
No automated test framework exists for these scripts; the checks below are required evidence in the handoff
(command plus the relevant output lines):
- `scripts/k8s/lint.sh` (shellcheck covers `env.sh`, `tools.sh`, `cluster-up.sh`, `cluster-down.sh`; kubeconform covers
  the three manifests).
- Two consecutive `scripts/k8s/cluster-up.sh` runs (AC 1), with the Secret `resourceVersion`s before and after.
- The checksum-mismatch run of `tools.sh` (AC 2, negative).
- `kube -n tastebuds get pods` and `kube -n tastebuds get events --field-selector reason=FailedCreate` (AC 5, empty
  when no pod was rejected).
- `redis-cli ping` (AC 6), then `scripts/k8s/cluster-down.sh` twice (AC 8).

## Quality gates
- `./gradlew check` passes (no Kotlin change expected).
- `scripts/k8s/lint.sh` passes locally and in CI.
- No new build dependency. New tools only as pinned, checksum-verified downloads into the git-ignored `.tools/`
  (justified: one-command local cluster with Docker as the only prerequisite) and pinned container images.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
