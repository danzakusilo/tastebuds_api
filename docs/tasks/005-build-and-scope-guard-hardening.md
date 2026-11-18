# 005 — Reproducible build inputs, dependency locking, fail-closed scope guard

- **Status:** READY
- **Phase:** 0 (hardening from the 2026-11-15 external review, items 6, 7 and 8)
- **Planned by:** claude-opus-5-5 on 2026-11-15
- **Depends on:** none (independent of 002–004; run it last or first, it touches no application code)

## Goal
Close three tooling gaps (docs/PLAN.md §8.5, §9, docs/agentic/WORKFLOW.md):
1. `springBoot { buildInfo() }` writes the build time, so every build changes `build-info.properties` and defeats
   up-to-date checks / build cache for `processResources`, `test` and everything downstream.
2. The agentic scope guard can be bypassed: (a) the active task spec is always editable, so an implementer can widen
   its own **Allowed paths**; (b) the hook exits with a non-2 code on its own errors (missing `jq`, bad JSON, git
   failure), which Claude Code treats as "allow"; (c) `scope.sh diff` swallows git errors (`|| true`) and then prints
   "all changes within scope".
3. Trivy's filesystem scan in CI sees no Gradle dependencies because there are no lockfiles. Enable Gradle dependency
   locking for every project (including `build-logic`), commit the lockfiles, and document the `--write-locks` workflow.

## Non-goals
- Gradle dependency **verification** (`verification-metadata.xml`), CycloneDX SBOM, pinning GitHub Actions
  (`docs/backlog/2026-11-15-pin-github-actions.md`), Renovate config changes (Renovate updates Gradle lockfiles natively).
- Changing `.claude/settings.json` (permissions are the user's call; see "Note for the user" below), CLAUDE.md, PLAN.md.
- Protecting other agentic files (agents, skills, hook) beyond what Allowed paths already enforce.
- Any application code or test under `services/` or `libs/`.

## Allowed paths
- `build-logic/src/main/kotlin/tastebuds.spring-service.gradle.kts`
- `build-logic/src/main/kotlin/tastebuds.kotlin.gradle.kts`
- `build-logic/src/main/kotlin/tastebuds.coverage-aggregate.gradle.kts`
- `build-logic/build.gradle.kts`
- `gradle/libs.versions.toml`
- `build-logic/gradle.lockfile`
- `gradle.lockfile`
- `libs/observability/gradle.lockfile`
- `services/core/gradle.lockfile`
- `settings-gradle.lockfile`
- `build-logic/settings-gradle.lockfile`
- `.claude/hooks/scope-guard.sh`
- `.claude/skills/run-task/SKILL.md`
- `scripts/agentic/scope.sh`
- `scripts/agentic/task-meta.sh`
- `scripts/agentic/scope_test.sh`
- `.github/workflows/ci.yml`
- `docs/agentic/WORKFLOW.md`
- `docs/build/DEPENDENCY_LOCKING.md`

## Design notes

### 1. Build info without time (`tastebuds.spring-service.gradle.kts`)
```kotlin
springBoot {
    // No timestamp: it would change build-info.properties on every build and defeat up-to-date checks and the cache.
    buildInfo { excludes.set(setOf("time")) }
}
```
(`BuildInfo.getExcludes()` exists in the Boot 4.1.2 Gradle plugin.)

### 2. Dependency locking
- `tastebuds.kotlin.gradle.kts` (applies to every module) and `tastebuds.coverage-aggregate.gradle.kts` (root) and
  `build-logic/build.gradle.kts` each get:
  ```kotlin
  dependencyLocking { lockAllConfigurations() }

  // Writes/refreshes gradle.lockfile: ./gradlew resolveAndLockAll --write-locks (see docs/build/DEPENDENCY_LOCKING.md)
  tasks.register("resolveAndLockAll") {
      notCompatibleWithConfigurationCache("Resolves configurations at execution time")
      doFirst {
          require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
      }
      doLast {
          configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
      }
  }
  ```
  Lock mode stays Gradle's default (a resolved version that differs from the lockfile fails the build; a
  configuration without lock state is allowed). That default is what makes a catalog bump without
  `--write-locks` fail `./gradlew check`.
- If a specific configuration cannot be resolved standalone by `resolveAndLockAll`, deactivate locking for that one
  configuration by name (`configurations.named("x") { resolutionStrategy.deactivateDependencyLocking() }`), comment why,
  list it in the doc, and mention it in the implementer report. Do not switch to a different locking scheme.
- Generate with `./gradlew resolveAndLockAll --write-locks` and `./gradlew -p build-logic resolveAndLockAll --write-locks`.
  Commit exactly the six lockfiles in Allowed paths: four `gradle.lockfile`s plus the two `settings-gradle.lockfile`s
  that Gradle writes for the settings-level version catalog configuration (`empty=incomingCatalogForLibs0`; amended
  2026-11-18 by the user after round 1). If Gradle produces any other lockfile, stop and report `SCOPE_BLOCKED` with
  its path.
- `docs/build/DEPENDENCY_LOCKING.md`: why (reproducible resolution; Trivy fs reads `gradle.lockfile`), the two
  commands above, when to run them (after any change to `libs.versions.toml`, build scripts, or plugin versions;
  Renovate PRs already include refreshed lockfiles), how a stale lockfile shows up (resolution failure naming the
  module), and the list of configurations excluded from locking (if any).
- Build-tool CVE override (amended 2026-11-18 by the user after the PR's Trivy fs scan failed): Kover 0.9.11 (latest)
  puts `org.freemarker:freemarker:2.3.32` (CVE-2026-84939, CRITICAL, fixed in 2.3.35) on the `build-logic` classpath.
  In `gradle/libs.versions.toml` add only `freemarker = "2.3.35"` under `[versions]` and
  `freemarker = { module = "org.freemarker:freemarker", version.ref = "freemarker" }` under `[libraries]`. In
  `build-logic/build.gradle.kts` add `constraints { implementation(libs.freemarker) { because("…") } }` naming the
  CVE and Kover, then refresh `build-logic/gradle.lockfile`. Remove the constraint once Kover ships 2.3.35 or later.

### 3. Scope guard

#### `scripts/agentic/scope.sh`
- `allowed <task> <path>`: the task spec itself is **never** allowed (even if a glob such as `docs/**` matches it).
  `docs/backlog/**` stays always allowed. Paths outside the repo stay denied. Exit codes unchanged (0 allowed, 1 denied, 2 usage/error).
- `diff <task> [base]` (fail on git errors, exit 2, never print the success line in that case):
  1. `merge_base="$(git -C "$root" merge-base "$base" HEAD)"` or fail with `scope: cannot determine merge base of '<base>' and HEAD` (exit 2).
  2. Collect changed files into a variable from `git diff --name-only "$merge_base" HEAD`, `git diff --name-only HEAD`,
     `git ls-files --others --exclude-standard`; if any of these fails → `scope: git failed while listing changes` (exit 2).
     No `2>/dev/null || true`.
  3. The spec file may change (status, review log), **except** its `## Allowed paths` section: if the spec exists at
     `merge_base` (`git cat-file -e "$merge_base:$task_rel"`) and `patterns` of that version differ from `patterns` of
     the working-tree version, print `OUT OF SCOPE: <spec> (Allowed paths changed since <short merge base>)` and count
     it as a violation. A spec that is new on the branch is not compared.
  4. Other files: unchanged rule (`docs/backlog/**` and matching globs allowed).
- Update the header comment to describe these rules.

#### `.claude/hooks/scope-guard.sh` (fail closed)
- First lines after `set -euo pipefail`: `trap 'echo "scope-guard: internal error (line $LINENO); blocking the edit (fail closed)" >&2; exit 2' ERR`.
- Explicit checks, each printing a reason to stderr and exiting **2**: `jq` not on PATH; stdin is not valid JSON
  (`jq -e . >/dev/null` on the captured input); repo root not determinable; active-task file present but empty;
  active spec file missing (scope.sh exits 2 → block).
- Read stdin once into a variable, then parse with `jq`.
- If the target is the active spec itself, block with a dedicated message: the spec is read-only during a task;
  Status and Review log are updated by the orchestrator with `scripts/agentic/task-meta.sh`; scope changes go back to the planner.
- Unchanged: no active-task file → exit 0; allowed path → exit 0; disallowed → existing message, exit 2.

#### `scripts/agentic/task-meta.sh` (new, executable)
```
task-meta.sh status <task-file> <STATUS>    # STATUS ∈ DRAFT READY IN_PROGRESS IN_REVIEW HANDOFF DONE
task-meta.sh log    <task-file> <file|->    # append the file (or stdin) to the end of the spec, under "## Review log"
```
- `<task-file>` must resolve inside `docs/tasks/` and not be `TEMPLATE.md` or `README.md`; it must contain exactly one
  line matching `^- \*\*Status:\*\* ` and a `## Review log` heading that is the last `## ` heading. Otherwise exit 2.
- `status` rewrites only that line to `- **Status:** <STATUS>`; `log` appends a blank line then the content. Writes via a
  temp file in the same directory + `mv`. Never touches any other line (in particular never the Allowed paths).
- This is the only supported way to edit an active spec, because the hook blocks file-tool edits to it.

#### `.claude/skills/run-task/SKILL.md`
Replace the instructions that edit the spec's status / review log directly with `scripts/agentic/task-meta.sh status …`
and `scripts/agentic/task-meta.sh log …` (write the round text to a temp file under the session scratchpad first).
No other changes to the skill's flow.

#### `scripts/agentic/scope_test.sh` (new, executable, plain bash, no new tools)
Creates a throwaway repo with `mktemp -d` (removed by `trap … EXIT`), copies `scope.sh`, `task-meta.sh` and the hook
into the same relative paths, sets `GIT_AUTHOR_NAME/EMAIL` and `GIT_COMMITTER_NAME/EMAIL` env vars locally, writes a
spec `docs/tasks/900-x.md` with allowed paths `src/a/**` and `docs/**`, commits it on `main`, branches `task/900-x`.
Each case prints `ok - <name>` or `not ok - <name>`; the script exits 1 if any case failed. Cases are listed under
Required tests. Hook cases run it with `CLAUDE_PROJECT_DIR=<tmp>` and JSON on stdin
(`{"tool_input":{"file_path":"<abs path>"}}`).

#### `.github/workflows/ci.yml`
New job (keep all existing jobs unchanged):
```yaml
  agentic-scripts:
    name: agentic scope scripts
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - run: shellcheck .claude/hooks/scope-guard.sh scripts/agentic/*.sh
      - run: scripts/agentic/scope_test.sh
```

#### `docs/agentic/WORKFLOW.md`
- Artifacts table: add `scripts/agentic/task-meta.sh` (orchestrator-only edits of Status / Review log) and
  `scripts/agentic/scope_test.sh`.
- "Scope enforcement, in layers": hook bullet adds "the active spec is read-only; the hook fails closed (blocks) on its
  own errors"; diff bullet adds "fails on git errors; flags any change to the spec's Allowed paths since the merge base".
- One sentence: an amended Allowed paths section must be merged to `main` before the task branch is re-run (rebase),
  because CI compares against the merge base.

### Implementation order (self-lock hazard)
The hook in the working tree guards the implementer's own edits. Edit and test `scope.sh` and `scope_test.sh` first,
then replace the hook in a single `Write`, then run `scope_test.sh` and `shellcheck`. If a broken hook blocks all edits,
stop and report `SCOPE_BLOCKED` (do not repair it via shell).

## Acceptance criteria
1. `build/resources/main/META-INF/build-info.properties` of `:services:core` has no `build.time`; running
   `./gradlew :services:core:test` twice in a row reports the second `test` task as `UP-TO-DATE` or `FROM-CACHE`.
2. The six lockfiles exist and are committed; `./gradlew check` passes with locking active; temporarily changing a
   version in `libs.versions.toml` without `--write-locks` makes dependency resolution fail (reviewer spot check, reverted).
3. `docs/build/DEPENDENCY_LOCKING.md` documents the commands and workflow.
4. With an active task, the hook blocks (exit 2) edits to the active spec even when a glob matches it.
5. The hook exits 2 (never 0 or 1) on: `jq` missing/failing, malformed JSON input, empty active-task file, missing spec.
6. `scope.sh diff` exits 2 and does not print `all changes within` when the base ref does not exist or there is no merge base.
7. `scope.sh diff` reports a violation when the spec's Allowed paths section changed since the merge base, and does
   not when only Status / Review log changed.
8. `task-meta.sh` updates Status and appends to the Review log without touching other lines, and rejects invalid
   statuses and non-spec files with exit 2.
9. `shellcheck` is clean for the hook and all `scripts/agentic/*.sh`; the new CI job is present; run-task SKILL.md and
   WORKFLOW.md describe the new flow.

## Required tests
- `scripts/agentic/scope_test.sh` cases (names as printed):
  - `allowed: matching glob is allowed`, `allowed: non-matching path is denied`, `allowed: backlog is always allowed`,
    `allowed: path outside the repo is denied`, `allowed: the spec itself is denied even when a glob matches` (AC4)
  - `diff: in-scope change passes`, `diff: out-of-scope change fails with exit 1`,
    `diff: unknown base ref fails with exit 2 and no success line`, `diff: no merge base fails with exit 2`
    (orphan branch via `git checkout --orphan`) (AC6),
    `diff: status-only spec change passes`, `diff: allowed-paths change in the spec fails` (AC7)
  - `hook: no active task allows everything`, `hook: allowed path passes`, `hook: out-of-scope path is blocked with exit 2`,
    `hook: active spec is blocked with exit 2` (AC4), `hook: malformed JSON is blocked with exit 2`,
    `hook: failing jq is blocked with exit 2` (put a fake `jq` that exits 1 first on PATH),
    `hook: empty active-task file is blocked with exit 2`, `hook: missing spec is blocked with exit 2` (AC5)
  - `task-meta: status rewrites only the status line` (diff of before/after is exactly that line),
    `task-meta: log appends under the review log`, `task-meta: invalid status exits 2`,
    `task-meta: non-spec file exits 2` (AC8)
- Gradle: AC1 and AC2 are verified by running the commands; no Kotlin test needed.

## Quality gates
- `./gradlew check` passes (ktlint, detekt, unit + integration tests, ArchUnit, Spring Modulith verification).
- `scripts/agentic/scope_test.sh` passes; `shellcheck` clean.
- No new dependency (shellcheck is preinstalled on GitHub's ubuntu runners; locally it is optional for the implementer
  if not installed, in which case say so in the report).

## Note for the user (not part of the implementation)
After this task the orchestrator updates specs via `scripts/agentic/task-meta.sh`. You may want to add
`Bash(scripts/agentic/task-meta.sh:*)` to the `allow` list in `.claude/settings.json` to avoid prompts; the task does
not change permissions.

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->
