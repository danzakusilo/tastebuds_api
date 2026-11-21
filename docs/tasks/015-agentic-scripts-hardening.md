# 015 — Harden the agentic scope scripts: fail-closed hook, safe review-log text, tested git listings

- **Status:** HANDOFF
- **Phase:** 1 (agentic tooling hardening from the 2026-11-21 spec audit, Part E: E1–E7)
- **Planned by:** claude-opus-5-5 on 2026-11-21
- **Depends on:** none
- **Resolves backlog:** `docs/backlog/2026-11-18-scope-test-strength.md` and
  `docs/backlog/2026-11-18-scope-diff-misses-renames.md` (Design notes §6). Delete both files.

## Goal
Make the scope enforcement layers of docs/agentic/WORKFLOW.md ("Scope enforcement, in layers"; docs/PLAN.md §9, D9)
do what they claim. The 2026-11-21 audit found, with mutants and scratch repos, that the hook can still exit with a
status Claude Code treats as "allow" (`set -u` errors and errors inside functions skip the ERR trap), allows input
without a usable path during a task, blocks every edit when jq is missing even with no task, and gives no way out of a
stale `.claude/active-task`; that `task-meta.sh log` can append text that `scope.sh` then reads as a second Allowed
paths section and that breaks every later `task-meta.sh` call; and that no test covers the committed-change listing
that CI depends on, while most negative tests accept any exit 2. After this task each allow path of the hook is listed
and tested, every unexpected status becomes a block, review-log text cannot change the spec structure, every git
listing of `scope.sh diff` is tested for both a violation and a git failure, and every negative test names its reason.

## Non-goals
- Writes through the shell (`cat >`, `sed -i`, `rm .claude/active-task`, signals sent to the hook):
  `docs/backlog/2026-11-18-scope-guard-ignores-shell-writes.md` stays open. This task hardens what the hook sees.
- CI trusting a spec that is new on the branch (`docs/backlog/2026-11-18-scope-ci-trusts-branch-spec.md`); no change
  to `.github/workflows/ci.yml`.
- The default base of `scope.sh diff` (`docs/backlog/2026-11-15-scope-diff-default-base.md`).
- Portability and small `task-meta.sh` edge cases (`docs/backlog/2026-11-18-agentic-scripts-portability.md`: GNU-only
  tools, the missing blank line when a spec has no trailing newline, running from a subdirectory).
- run-task preflight checks (`docs/backlog/2026-11-18-run-task-preflight-amended-scope.md`), including the optional
  audit idea of a preflight warning when `.claude/active-task` exists.
- Edits to `docs/agentic/WORKFLOW.md` and `.claude/skills/run-task/SKILL.md` (open PR #10 changes both; the recovery
  hint lives in the hook's messages and header instead), `.claude/settings.json` (matcher, hook timeout and permissions
  are the user's call), `CLAUDE.md`, `README.md` (no jq line: the hook's message names jq when it is needed),
  `docs/tasks/TEMPLATE.md`, `docs/agentic/SPEC_CHECKLIST.md`.
- Traps for TERM, INT or HUP in the hook (see Limits: only Claude Code's timeout or a shell process can signal it).
- Checking the content of `.claude/active-task` beyond §1 steps 4–7 (for example requiring `docs/tasks/NNN-*.md`).
- New subcommands, new options, or changed exit codes of `scope.sh` and `task-meta.sh`; reworded messages other than
  those §1–§3 list.

## Allowed paths
- `.claude/hooks/scope-guard.sh`
- `scripts/agentic/scope.sh`
- `scripts/agentic/task-meta.sh`
- `scripts/agentic/scope_test.sh`
- `docs/backlog/2026-11-18-scope-test-strength.md`
- `docs/backlog/2026-11-18-scope-diff-misses-renames.md`

## Design notes

### 0. Findings and where they are fixed
| Finding (audit Part E) | Fix |
|---|---|
| E1 committed listing untested | §4 cases for a committed violation, a committed Allowed-paths change and a committed rename; a fake git that fails each listing and the spec read in turn; distinct messages per listing (§2) |
| E2 `log` text read as structure | §3 `log` rejects lines that start with `## ` or `- **Status:** `; §2 `patterns` reads only the first Allowed paths section |
| E3 input without a path allowed; no NotebookEdit test | §1 step 11 blocks unless exactly one of `file_path` / `notebook_path` is an absolute string without control characters; §4 NotebookEdit cases |
| E4 negative tests check only exit 2 | §4 every negative case asserts its message (and the hint where §1 prints it) |
| E5 jq missing blocks with no task | §1 order: the active-task check (step 4) comes before every jq and stdin check |
| E6 stale `.claude/active-task` | §1 hint line in every block after step 4; lifecycle in the hook header |
| E7 ERR trap misses `set -u` and function errors | §1 EXIT trap maps every status other than 0 and 2 to 2 |
| Backlog: rename hides the source path | §2 `--no-renames` on both `git diff` listings (same lines E1 tests; two flags) |
| Backlog: scope_test strength | §4 second fixture spec, failing `scope.sh` case, per-function shellcheck directives |

Notation in this spec: `<task>` is the content of `.claude/active-task` with all whitespace removed (the repo-relative
spec path). `<file>` is the path from the tool input, unchanged. `<rc>` is an exit status as a decimal number. `<repo>`
is the throwaway repository of `scope_test.sh` (`$repo`). `<v>` is a shellcheck image tag. In §5,
`<session scratchpad>` is any directory outside the repository (the orchestrator's session scratchpad),
`<file changed last>` is the repo-relative path of the file the implementer changed last, `<probe output>` is the full
output of the probe commands, and `<same arguments>` are the arguments of the `task-meta.sh` call that failed.

### 1. `.claude/hooks/scope-guard.sh`
Rewrite the hook with this exact order of steps. Every block prints to stderr and exits 2.

1. `set -euo pipefail`, then the helpers below, then the line `trap on_exit EXIT` (exactly this text on its own line;
   the test in §4 anchors on it). No command that can fail runs before the trap.
   ```bash
   hint=""   # set after step 4; printed under every later block message
   say_block() {
     echo "scope-guard: $*; blocking the edit (fail closed)" >&2
     [[ -z "$hint" ]] || echo "$hint" >&2
   }
   block() { say_block "$@" || true; exit 2; }
   # shellcheck disable=SC2317,SC2329  # invoked only by the EXIT trap
   on_exit() {
     local rc=$?
     if (( rc != 0 && rc != 2 )); then
       say_block "internal error (exit $rc)" || true
       exit 2
     fi
   }
   trap on_exit EXIT
   ```
   The `|| true` is required: a failing command inside an EXIT trap ends the shell with its own status (verified), so
   a failed `echo` (closed stderr) would otherwise turn a block into an allow. Remove the ERR trap.
2. `input="$(cat)"`: read stdin once, before any exit, so Claude Code never writes a large input into a closed pipe.
3. Repository root: `CLAUDE_PROJECT_DIR`, else `git rev-parse --show-toplevel`; if neither gives an existing
   directory, block `cannot determine the repository root` (unchanged; no hint yet).
4. `active_file="$root/.claude/active-task"`. If it neither exists (`-e`) nor is a symlink (`-L`): `exit 0`
   (**allow case A**: no task is active, the hook restricts nothing, whatever stdin holds and whether jq exists). Then
   set `hint` to exactly:
   `If no /run-task is in progress (it ended, crashed, or the branch changed), .claude/active-task is stale. Only the user deletes a stale file; the run-task orchestrator deletes it only at handoff. Do not delete it to get past this block: report this message (implementers: SCOPE_BLOCKED).`
5. Not a regular file (`-f` false, for example a directory or a dangling symlink): block
   `.claude/active-task is not a regular file`.
6. `task="$(tr -d '[:space:]' < "$active_file")"`; empty: block `.claude/active-task is empty`.
7. `$root/$task` is not a regular file: block `active task spec not found: <task> (named in .claude/active-task)`.
8. `command -v jq` fails: block `jq is not installed (it is required while a task is active)`.
9. stdin empty or whitespace only: block `stdin is empty` (jq 1.6 accepts empty input, keep the explicit check).
10. `printf '%s' "$input" | jq -e -s 'length == 1 and (.[0] | type) == "object"'` fails: block
    `stdin is not a single JSON object`.
11. Path extraction, exactly this call (the fake jq in §4 recognises it by the first argument `-r`):
    ```bash
    file="$(printf '%s' "$input" | jq -r '[.tool_input.file_path, .tool_input.notebook_path] | map(select(. != null))
      | if length == 1 and (.[0] | type) == "string" and (.[0] | startswith("/")) and (.[0] | explode | all(. >= 32))
        then .[0] else empty end')" \
      || block "jq failed to read the tool input"
    ```
    Empty `file`: block
    `the tool input has no usable path (exactly one of file_path or notebook_path, absolute, without control characters)`.
    Reasons: no field or an empty or non-string value must not mean "allow" (E3); both fields at once is ambiguous
    (the hook could check one and the tool write the other); a relative path would be resolved against the hook's
    working directory here but against the root in `scope.sh`; a control character (U+0000–U+001F) changes the path,
    because bash drops NUL bytes from `$(...)` (verified: `/r/src/b\0/../a/x` becomes `/r/src/b/../a/x`, which
    resolves into `src/a`). Edit and Write send an absolute `file_path` (verified); MultiEdit (`file_path`) and
    NotebookEdit (`notebook_path`) are taken from today's hook (UNVERIFIED, see Assumptions). A wrong field name
    blocks that tool during a task; it never allows an edit.
12. Target is the active spec (`realpath -m` of both equal): the existing read-only message, unchanged, plus the hint
    as its last line; exit 2.
13. `(cd "$root" && scripts/agentic/scope.sh allowed "$task" "$file") || rc=$?`: 0 → `exit 0` (**allow case B**);
    1 → the existing out-of-scope message, unchanged, plus the hint as its last line, exit 2; any other status → block
    `scope.sh failed with exit <rc>`.

After the change the hook's code (comment lines excluded) contains exactly two `exit 0` (steps 4 and 13) and no other
way to exit 0.

Header comment (replace lines 2–6): what the hook guards and that with no active task nothing is restricted; the two
allow cases; the lifecycle of `.claude/active-task` (git-ignored; holds `<task>`; the run-task skill writes it in
preflight and deletes it at handoff; after a crash, an abort or a branch switch it can be stale, and then only the user
deletes it with `rm .claude/active-task`; the hook never writes or deletes it); jq is needed only while a task is
active; fail closed: the EXIT trap turns every status other than 0 and 2 into 2, because Claude Code treats other
codes as "allow". Keep it at most 12 lines.

#### Allow cases and fallbacks (checklist rule 7)
The client here is the guarded agent: it chooses tool inputs and review text, and it cannot set the hook's environment
or stdin framing (Claude Code builds the JSON).
- Allow case A needs `.claude/active-task` to be absent. File tools cannot delete files; a Write to that path is
  blocked unless a spec lists it (none does), and an emptied file blocks (step 6). Only the shell can remove it
  (non-goal, backlog).
- Allow case B needs `scope.sh allowed` to accept the path. Step 11 makes the checked path the written path: one JSON
  object, one field, absolute, no control characters.
- Claude Code treats exit codes other than 0 and 2 as "allow". Covered: every status the script can reach goes
  through the EXIT trap. A syntax error that bash reaches exits 2 on its own. Left: bash killed by a signal (status
  143, the trap does not run). Only Claude Code's timeout or a shell process sends one; an agent cannot make the hook
  run near the timeout (Limits). A hook file that is missing or not executable gives 126/127. In this task the
  implementer rewrites the hook, so §5 and acceptance criterion 11 require the 0755 mode to stay.
- Tool names: the settings matcher `Edit|Write|MultiEdit|NotebookEdit` is a list of exact names in Claude Code, so
  other tools (for example TodoWrite) never reach the hook, and step 11 cannot block them.
- Library steps on untrusted input: jq parses the tool input (no I/O or network side effects); `realpath -m` reads only
  the file system; `<file>` appears only in the stderr message that goes back to the same agent.

### 2. `scripts/agentic/scope.sh`
- `patterns` reads only the first `## Allowed paths` section and always reads all its input:
  ```bash
  awk '/^## Allowed paths/{f=!seen; seen=1; next} /^## /{f=0} f && /^- `/{sub(/^- `/,""); sub(/`.*$/,""); print}'
  ```
  No early `exit` in awk: in `git show … | patterns` under `pipefail` an early exit kills `git show` with SIGPIPE
  (status 141) when the spec is larger than the 64 KiB pipe buffer (verified with 300 KB; specs reach 44 KB today).
- `diff` listings, each with its own message:
  ```bash
  committed="$(git -C "$root" diff --name-only --no-renames "$merge_base" HEAD)" \
    || { echo "scope: git failed while listing committed changes" >&2; exit 2; }
  uncommitted="$(git -C "$root" diff --name-only --no-renames HEAD)" \
    || { echo "scope: git failed while listing uncommitted changes" >&2; exit 2; }
  untracked="$(git -C "$root" ls-files --others --exclude-standard)" \
    || { echo "scope: git failed while listing untracked files" >&2; exit 2; }
  changes="$committed"$'\n'"$uncommitted"$'\n'"$untracked"
  ```
  `--no-renames` lists a rename as the deleted old path plus the added new path, so moving a file out of a protected
  path is reported. Everything else in `diff` stays as it is.
- Header comment: Allowed paths come from the first `## Allowed paths` section only; `diff` reports both paths of a
  rename.

### 3. `scripts/agentic/task-meta.sh`
- `log` copies the text (file or stdin) into a second temp file, rejects it if any line would be read as structure,
  and only then builds the new spec:
  ```bash
  entry=""   # before the trap
  tmp="$(mktemp "$(dirname "$file")/.task-meta.XXXXXX")"
  trap 'rm -f -- "$tmp" ${entry:+"$entry"}' EXIT
  …
    log)
      if [[ "$arg" != "-" ]]; then
        [[ -f "$arg" ]] || die "log file not found: $arg"
      fi
      entry="$(mktemp)"
      cat -- "$arg" > "$entry"
      # The line patterns scope.sh and this script read as structure: "## " headings and the Status line.
      rc=0
      bad="$(grep -n -m 1 -E '^(## |- \*\*Status:\*\* )' "$entry")" || rc=$?
      case "$rc" in
        0) die "log text line ${bad%%:*} starts with '## ' or '- **Status:** ', which would change the spec structure; indent or reword it" ;;
        1) ;;
        *) die "grep failed while checking the log text" ;;
      esac
      { cat "$file"; echo; cat "$entry"; } > "$tmp"
      ;;
  ```
  The regex is the union of what the parsers read: `scope.sh` (`^## Allowed paths`, `^## `) and `task-meta.sh`
  (`^## `, `^- \*\*Status:\*\* `). `### Round N` lines do not match `^## `. If a parser pattern changes later, change
  this regex with it (say so in the comment).
- `entry` lives in `$TMPDIR` (no atomic rename needed), so a killed run leaves nothing in the repository.
  `${entry:+"$entry"}` keeps `rm` from getting an empty argument on the `status` path.
- Header: add that `log` rejects such lines with exit 2 and leaves the spec unchanged.

Lifecycle (checklist rule 6): `tmp` is created in `docs/tasks/`, renamed over the spec on success, and removed by the
EXIT trap on every other exit. It stays only after SIGKILL; then `scope.sh diff` reports it as `OUT OF SCOPE` and the
user deletes it (unchanged behaviour). `entry` is removed by the EXIT trap on every exit except SIGKILL, and it is never
inside the repository.

### 4. `scripts/agentic/scope_test.sh`
Keep the structure (`run`, `want`, `check`, one throwaway repo, `reset_repo`). Changes:
- Fixtures in `setup`: commit a non-empty `protected/p.kt` on `main` (git skips rename detection for empty files);
  add a second spec `docs/tasks/901-y.md` (`spec2`) whose only Allowed path is `` `**/x/**` ``, so the built-in rules
  (backlog always allowed, outside the repo denied) decide on their own.
- Fake jq (`$fakebin/jq`): exits 5 when its first argument is `-r` (the extraction call of §1 step 11), otherwise
  `exec`s the real jq. The JSON check passes and the extraction fails.
- No-jq PATH: a directory `$nojq` with symlinks to `bash cat tr git realpath awk sed sort env dirname basename mktemp
  grep` (every external command the hook and `scope.sh` use, except jq), used as the whole `PATH`.
- Fake git (`$fakegit/git`): fails (exit 1) when `$FAKE_GIT_FAIL` is set, the first argument is `-C`, and the
  arguments after `-C <dir>` joined with spaces (`"${*:3}"`) match the glob in `$FAKE_GIT_FAIL`; otherwise it `exec`s
  the real git. `scope_fake_git <glob> <scope.sh args…>` sets it. Globs: `diff --name-only --no-renames * HEAD`,
  `diff --name-only --no-renames HEAD`, `ls-files --others --exclude-standard`, `show *`.
- `notebook_json <path>` prints `{"tool_input":{"notebook_path":"<path>"}}`.
- `stale_hint="Only the user deletes a stale file"`, asserted wherever §1 prints the hint.
- Cases that change a committed file of the throwaway repo (hook variants, fake `scope.sh`) restore it right after the
  case with `git -C "$repo" checkout -q -- <file>`.
- shellcheck: remove the file-wide `# shellcheck disable=SC2317,SC2329`. Put `# shellcheck disable=SC2317,SC2329`
  directly above each helper that is called only through `run`/`want` (`out_has`, `out_lacks`, `scope`,
  `scope_fake_git`, `meta`, `hook`). A directive in a `case` goes inside the branch, not before its label (SC1124).
  Literal backticks in single quotes need `# shellcheck disable=SC2016` with a reason, as today.

The cases are listed under Required tests.

### 5. Self-lock hazard: the hook guards its own rewrite
The live hook is `.claude/hooks/scope-guard.sh` in this working tree, and it calls `scripts/agentic/scope.sh` from the
same tree. A broken hook or `scope.sh` blocks every edit, including the edit that would repair it.

Implementer:
1. Change the files in this order: `scope_test.sh` (its new cases fail until the scripts follow), `task-meta.sh`,
   `scope.sh`, the hook last.
2. Every Write or Edit of `scope.sh` or the hook must leave a complete, working file: do the first rewrite of each as
   one Write of the complete file, and later fixes as Edits that each stand alone (Write and Edit replace the file at
   once and keep mode 0755, verified). Before the rewrite, check the draft without writing a file:
   `bash -n <<'DRAFT_EOF'` … `DRAFT_EOF` (use a delimiter that does not occur in the draft).
3. Right after each change to `scope.sh` or the hook, before any other edit, run the probe from the repository root:
   ```bash
   printf '{"tool_input":{"file_path":"%s"}}' "$PWD/scripts/agentic/scope_test.sh" \
     | CLAUDE_PROJECT_DIR="$PWD" .claude/hooks/scope-guard.sh; echo "exit $?"
   printf '{"tool_input":{"file_path":"%s"}}' "$PWD/README.md" \
     | CLAUDE_PROJECT_DIR="$PWD" .claude/hooks/scope-guard.sh; echo "exit $?"
   test -x .claude/hooks/scope-guard.sh && test -x scripts/agentic/scope.sh && echo "modes ok"
   ```
   Expected: `exit 0` with no other output; then the out-of-scope message naming
   `docs/tasks/015-agentic-scripts-hardening.md` and `exit 2`; then `modes ok`. Then run
   `scripts/agentic/scope_test.sh`.
4. If the first probe does not print `exit 0`: repair the file with Edit or Write if the hook lets you. If the hook
   blocks that edit, stop. Do not repair anything through the shell and do not touch `.claude/active-task`. Report
   `SCOPE_BLOCKED: hook lockout — <file changed last> — <probe output>` and put the intended content of that file in
   the report.

Orchestrator, on `SCOPE_BLOCKED: hook lockout`: the run-task skill stops the loop and goes to Handoff. In Handoff,
before it deletes `.claude/active-task`:
1. Save the work on the guard files outside the repository:
   `git diff -- .claude/hooks/scope-guard.sh scripts/agentic/scope.sh > <session scratchpad>/015-lockout.diff`.
2. Restore the two files from the branch: `git checkout HEAD -- .claude/hooks/scope-guard.sh scripts/agentic/scope.sh`.
   This returns the guard to its last reviewed version and changes no file outside the Allowed paths; it is the
   recovery step this spec defines, not a way around the scope.
3. Run the first probe of step 3; it must print `exit 0`.
4. Continue the Handoff as the skill says. Report the lockout, the probe output and the path of the saved diff. The
   user decides about a new run, in which the implementer re-applies the diff.

Orchestrator, review log: from the moment the new `task-meta.sh` is in the tree, `log` rejects review text with a line
that starts with `## ` or `- **Status:** ` (findings of this task may quote such lines). Indent those lines and run
it again. If `task-meta.sh` fails for a reason other than its input (for example a half-finished change), run the
committed version instead: `bash <(git show HEAD:scripts/agentic/task-meta.sh) <same arguments>`.

### 6. Backlog
- Delete `docs/backlog/2026-11-18-scope-test-strength.md`. Its part 1 is covered by the `spec2` cases, part 2 by
  `hook: failing scope.sh is blocked with exit 2`, part 3 by the per-function directives.
- Delete `docs/backlog/2026-11-18-scope-diff-misses-renames.md`. Covered by `--no-renames` and the two rename cases.
- Leave the other agentic backlog items unchanged (see Non-goals).

## Assumptions verified
| Claim | How checked |
|---|---|
| Today's ERR trap does not run for a `set -u` error or a failing command inside a function; the hook then exits 1 (Claude Code: allow) | bash 5.1.16 scratch script with the current trap line: both exit 1, a plain failing command exits 2 |
| The §1 EXIT handler turns `set -u` errors, failures inside functions, failing `x="$(false)"`, pipeline failures, a missing command (127) and an explicit `exit 1` into exit 2 with `internal error (exit <rc>)`; `exit 0` and `exit 2` pass with no extra output; with stderr closed, blocks still exit 2 | bash 5.1.16 scratch script, each case run |
| A failing command inside an EXIT trap ends the shell with that status (`set -e; trap 'false; …' EXIT; exit 0` exits 1) | bash 5.1.16 |
| `( … ) \|\| rc=$?` keeps the child status; the EXIT trap does not run in the subshell | bash 5.1.16: `(exit 1)` gives rc=1, exit 0 overall |
| bash parses the script as it goes: a syntax error after a reached `exit 0` never runs; a reached syntax error exits 2 | bash 5.1.16 |
| SIGTERM ends bash with 143 and the EXIT trap does not run | bash 5.1.16, `kill -TERM` during `sleep` |
| Claude Code 2.1.330 reads a matcher of only `[a-zA-Z0-9_\|]` as exact tool names (plus aliases), not as a regex | read the matcher function in the installed 2.1.330 bundle: `/^[a-zA-Z0-9_\|]+$/` test, `split("\|")`, `includes(toolName)` |
| Claude Code treats hook exit codes other than 0 and 2 as non-blocking | partly: the 2.1.330 bundle has "Failed with non-blocking status code"; task 005 relies on it. What a hook timeout does to the edit: UNVERIFIED |
| Edit and Write send an absolute `tool_input.file_path` | the Edit and Write tool schemas in this planning session ("The absolute path to the file") |
| MultiEdit sends `tool_input.file_path`; NotebookEdit sends `tool_input.notebook_path` | UNVERIFIED: implementer checks first if these tools are available (today's hook already relies on both). If wrong, that tool's edits are blocked during a task (fail closed), not allowed |
| Claude Code's Write and Edit replace the file (new inode) and keep mode 0755 | ran both tools on a 0755 file in the scratchpad, `stat` before and after |
| The step 10 and step 11 jq filters give the same result in jq 1.6 (local) and jq 1.7.1 (`ghcr.io/jqlang/jq:1.7.1`; that GitHub's Ubuntu 24.04 runner has 1.7.1 is UNVERIFIED) | 17 inputs (valid paths, `{}`, `path` key, `""`, relative, number, array, newline, NUL, U+001F, both fields, UTF-8, non-object, `null`) and 9 stdin shapes (`{} {}`, empty, whitespace, `null`, array, string, malformed, trailing newline): identical results |
| bash drops NUL bytes in `$(...)` with a warning | bash 5.1.16 |
| Today's hook with an active task exits 0 for `{}`, `{"tool_input":{"path":…}}`, `"file_path":""`, a NUL path to an out-of-scope file, and an in-scope `file_path` with an out-of-scope `notebook_path` | the §4 cases run against the current hook in a scratch repo |
| Today's `task-meta.sh log` accepts `## Allowed paths` text (exit 0); every later `log`/`status` then exits 2 | the §4 case run against the current script |
| `git diff --name-only` lists only the new path of a staged or committed `git mv`; with `--no-renames` both | git 2.34.1 scratch repo (mutant without the flag fails the rename cases) |
| An awk early `exit` in `git show \| patterns` under `pipefail` gives 141 for a 300 KB spec; the §2 awk gives 0; the §2 awk gives the first section in gawk 5.1.0 and mawk | ran all three |
| shellcheck: a function used only by `trap on_exit EXIT` raises SC2317 (0.9.0, 0.10.0) or SC2329 (0.11.0); the directive above the function clears it; a directive before a `case` label is SC1124 | `koalaman/shellcheck:v0.9.0`, `v0.10.0`, `v0.11.0` on a prototype |
| The whole design works: a prototype of §1–§4 passes all 46 cases in 1.3 s, is shellcheck-clean in 0.9.0/0.10.0/0.11.0, every mutant in acceptance criterion 10 fails at least one case, and the new cases fail on today's scripts where the findings say | prototype in a scratch directory (deleted) |
| This spec works with the tools that read it, and the §5 steps work | scratch clone at 68a07fa plus this spec as the first commit, `.claude/active-task` set: today's `scope.sh allowed` gives 0 for the six Allowed paths and a new backlog file, 1 for `README.md`, `docs/agentic/WORKFLOW.md`, `.claude/skills/run-task/SKILL.md`, `.github/workflows/ci.yml`, `.claude/settings.json`, `.claude/active-task` and the spec; today's `task-meta.sh` (through `bash <(git show HEAD:…)`) runs `status` and `log` on the spec with exit 0; the §5 probes give `exit 0`, then the out-of-scope message with `exit 2`, then `modes ok`, with today's hook and with the prototype; with the prototype in place, acceptance criteria 1, 9, 11 and 13 give the stated output (uncommitted and committed); a hook with a syntax error makes the first probe exit 2, and after the saved diff and `git checkout HEAD -- …` it exits 0 |
| GitHub's `ubuntu-latest` shellcheck version | UNVERIFIED (Ubuntu 24.04 packages 0.9.0); the design is clean on 0.9.0, 0.10.0 and 0.11.0 |

## Limits
| Limit | Value | Wrapped by (must be larger) |
|---|---|---|
| Hook run time (stdin read, two jq calls, `scope.sh`) | measured 0.04 s for a normal input, 0.56 s for a 10 MiB tool input, 3.3 s for 50 MiB | Claude Code command-hook timeout, 60 s by default; `settings.json` sets none (UNVERIFIED docs value). When it fires, Claude Code stops the hook; nothing with side effects keeps running (`scope.sh` only reads). An agent cannot reach it: tool input is bounded by the model's output, far below 50 MiB |
| Tool input held in memory by the hook | not limited by the hook | the model's tool-call output size (see the row above) |
| Pipe buffer between `git show` and `patterns` | 64 KiB on Linux; largest spec today 44 KB | not a limit once `patterns` reads all input (§2) |
| `scope_test.sh` run time | 1.3 s (prototype, 46 cases) | CI `agentic-scripts` job: no `timeout-minutes`, GitHub default 360 min |

## Acceptance criteria
1. The hook follows §1 steps 1–13 in that order.
   `grep -v '^[[:space:]]*#' .claude/hooks/scope-guard.sh | grep -c 'exit 0'` prints `2`;
   `grep -c '^trap on_exit EXIT$' .claude/hooks/scope-guard.sh` prints `1`;
   `grep -cE '^trap .*ERR' .claude/hooks/scope-guard.sh` prints `0`.
2. With no `.claude/active-task`, the hook exits 0 with no output for any stdin, also without jq on `PATH`.
3. With an active task, every exit status other than 0 and 2 becomes 2 with `internal error (exit <rc>)`, shown for an
   unbound variable and for a failing command inside a function.
4. With an active task, the hook blocks (exit 2, §1 step 11 message) unless exactly one of `file_path` and
   `notebook_path` is an absolute string without control characters; `notebook_path` is checked against the Allowed
   paths like `file_path`.
5. Every block after §1 step 4 ends with the hint line; the messages are the ones §1 lists.
6. `scope.sh` reads only the first Allowed paths section, lists renames with both paths, and prints the three
   distinct listing messages of §2.
7. `task-meta.sh log` rejects text with a line that starts with `## ` or `- **Status:** ` (file and stdin), exit 2,
   the spec byte-identical, no `.task-meta.*` file left in `docs/tasks/`.
8. `scripts/agentic/scope_test.sh` prints `ok -` for every case under Required tests (46) and no `not ok`; every
   negative case asserts its message.
9. `awk '/^set -uo pipefail/{exit} /^[[:space:]]*# shellcheck disable=/{print}' scripts/agentic/scope_test.sh`
   prints nothing (no file-wide directive).
10. Mutation check (reviewer): copy `.claude/hooks/scope-guard.sh` and `scripts/agentic/*.sh` into a `mktemp -d`
    directory with the same relative paths, apply one mutant, run the copy's `scripts/agentic/scope_test.sh`; each
    mutant makes at least the named case print `not ok`:
    | Mutant | Case that must fail |
    |---|---|
    | `scope.sh`: drop `"$committed"` from `changes` | `diff: committed out-of-scope change fails with exit 1` |
    | `scope.sh`: drop `--no-renames` from the committed listing | `diff: committed rename out of a protected path fails` |
    | `scope.sh`: drop `--no-renames` from the uncommitted listing | `diff: staged rename out of a protected path fails` |
    | `scope.sh`: uncommitted listing `\|\| true` instead of exit 2 | `diff: failing uncommitted listing fails with exit 2 and no success line` |
    | `scope.sh`: untracked listing `\|\| true` instead of exit 2 | `diff: failing untracked listing fails with exit 2 and no success line` |
    | `scope.sh`: spec read at the merge base `\|\| true` instead of exit 2 | `diff: failing read of the spec at the merge base fails with exit 2 and no success line` |
    | `scope.sh`: `patterns` reads every section (`{f=1; next}`) | `allowed: only the first Allowed paths section counts` |
    | `scope.sh`: drop `[[ "$path" != ../* ]]` | `allowed: path outside the repo is denied even when a glob matches` |
    | `scope.sh`: drop the `docs/backlog/*` rule | `allowed: backlog is always allowed without a matching glob` |
    | hook: extract only `file_path` | `hook: out-of-scope notebook_path is blocked with exit 2` |
    | hook: `exit 0` when `file` is empty | `hook: input without exactly one absolute path is blocked with exit 2` |
    | hook: drop the `startswith("/")` condition | `hook: input without exactly one absolute path is blocked with exit 2` |
    | hook: drop the control-character condition | `hook: input without exactly one absolute path is blocked with exit 2` |
    | hook: `jq -e .` instead of the step 10 check | `hook: more than one JSON value is blocked with exit 2` |
    | hook: jq check moved before step 4 | `hook: no active task allows the edit without jq or valid input` |
    | hook: an undefined command before the out-of-scope message | `hook: out-of-scope path is blocked with exit 2` |
    | hook: the old ERR trap instead of `trap on_exit EXIT` | `hook: an unexpected error (unbound variable, failure in a function) is blocked with exit 2` |
    | hook: non-regular active-task file → `exit 0` | `hook: active-task that is not a regular file is blocked with exit 2` |
    | hook: `scope.sh` status ≥ 2 treated as denied | `hook: failing scope.sh is blocked with exit 2` |
    | hook: hint text changed | `hook: missing spec is blocked with exit 2` |
    | `task-meta.sh`: content check skipped | `task-meta: log rejects text that adds a '## ' heading or a Status line and leaves the spec unchanged` |
11. `git ls-files -s .claude/hooks/scope-guard.sh scripts/agentic/` shows `100755` for all four files and
    `git diff --summary origin/main...HEAD -- .claude/hooks scripts/agentic | grep -c 'mode change'` prints `0`.
12. Both backlog files of §6 are deleted; no other file under `docs/backlog/` changes except new findings.
13. On the task branch, `scripts/agentic/scope.sh diff docs/tasks/015-agentic-scripts-hardening.md origin/main` prints
    `scope: all changes within docs/tasks/015-agentic-scripts-hardening.md`.

## Required tests
`scripts/agentic/scope_test.sh`, cases printed exactly with these names (existing names kept where the case only gets
stronger). Every negative case asserts the exit status and the message in quotes. Shared contracts are tested for each
part (checklist rule 3): both path fields (allow and block), each of the three listings and the spec read (violation
and git failure), both `log` input modes (append and reject). Every hook case that blocks after §1 step 4 also asserts
the hint (`$stale_hint`); only the unexpected-error case does not, because its injected line runs before step 4.
- `allowed:`
  - `allowed: matching glob is allowed`, `allowed: non-matching path is denied` (unchanged).
  - `allowed: backlog is always allowed without a matching glob`: `spec2`, `docs/backlog/x.md` → 0 (replaces
    `allowed: backlog is always allowed`).
  - `allowed: path outside the repo is denied even when a glob matches`: `spec2`, `$tmp/x/y` (matches `**/x/**` after
    `realpath --relative-to`) → 1 (replaces `allowed: path outside the repo is denied`).
  - `allowed: the spec itself is denied even when a glob matches` (unchanged).
  - `allowed: only the first Allowed paths section counts`: append a blank line, a `## Allowed paths` heading line and
    a `` - `**` `` line to the spec; `src/b/x.kt` → 1.
- `diff:`
  - `diff: in-scope change passes`: also asserts `scope: all changes within`.
  - `diff: untracked out-of-scope file fails with exit 1` (the current out-of-scope case, renamed): `OUT OF SCOPE: src/b.kt`.
  - `diff: uncommitted out-of-scope change fails with exit 1`: modify the tracked `protected/p.kt`; `OUT OF SCOPE: protected/p.kt`.
  - `diff: committed out-of-scope change fails with exit 1`: commit `src/b.kt`, clean tree; `OUT OF SCOPE: src/b.kt`.
  - `diff: staged rename out of a protected path fails` and `diff: committed rename out of a protected path fails`:
    `git mv protected/p.kt src/a/p.kt` (then commit); both assert `OUT OF SCOPE: protected/p.kt`.
  - `diff: backlog change passes without a matching glob`: `spec2`, untracked `docs/backlog/n.md` → 0.
  - `diff: unknown base ref fails with exit 2 and no success line`: asserts
    `cannot determine merge base of 'no-such-ref' and HEAD`.
  - `diff: no merge base fails with exit 2`: asserts `cannot determine merge base of 'main' and HEAD`.
  - `diff: failing committed listing fails with exit 2 and no success line` (replaces `diff: failing git diff fails
    with exit 2 and no success line`), `diff: failing uncommitted listing fails with exit 2 and no success line`,
    `diff: failing untracked listing fails with exit 2 and no success line`: one fake-git glob each (§4), with a
    committed in-scope change present; each asserts its §2 message and no `all changes within`.
  - `diff: failing read of the spec at the merge base fails with exit 2 and no success line`: Status changed with
    `task-meta.sh`, glob `show *`; asserts `git failed while reading the spec at the merge base`.
  - `diff: status-only spec change passes` (unchanged).
  - `diff: uncommitted allowed-paths change in the spec fails` (the current case, renamed) and
    `diff: committed allowed-paths change in the spec fails`: both assert `Allowed paths changed`.
- `hook:`
  - `hook: no active task allows everything`: exit 0 and empty output.
  - `hook: no active task allows the edit without jq or valid input`: `PATH="$nojq"`, stdin `not json{` → exit 0, empty output.
  - `hook: allowed path passes` and `hook: allowed notebook_path passes`: both exit 0 with empty output.
  - `hook: out-of-scope path is blocked with exit 2` and `hook: out-of-scope notebook_path is blocked with exit 2`:
    `outside the allowed paths of docs/tasks/900-x.md`.
  - `hook: active spec is blocked with exit 2`: `read-only`.
  - `hook: malformed JSON is blocked with exit 2` and `hook: more than one JSON value is blocked with exit 2` (two
    edit objects separated by a space): `stdin is not a single JSON object`.
  - `hook: empty stdin is blocked with exit 2`: `stdin is empty`.
  - `hook: failing jq is blocked with exit 2`: fake jq of §4; `jq failed to read the tool input`.
  - `hook: missing jq is blocked with exit 2 during a task`: `PATH="$nojq"`; `jq is not installed`.
  - `hook: input without exactly one absolute path is blocked with exit 2`: each of `{}`,
    `{"tool_input":{"path":"<repo>/src/a/x.kt"}}`, a relative `src/a/x.kt`, the number `5`, `""`,
    `"<repo>/src/b\u0000/../a/x.kt"`, and an in-scope `file_path` with an out-of-scope `notebook_path`; each exit 2 with
    `no usable path`.
  - `hook: failing scope.sh is blocked with exit 2`: replace the repo's `scope.sh` with a script that exits 3;
    `scope.sh failed with exit 3`.
  - `hook: an unexpected error (unbound variable, failure in a function) is blocked with exit 2`: assert the line
    `trap on_exit EXIT` occurs exactly once in the hook copy; insert after it, in turn, `: "$scope_test_unbound_variable"`
    and `scope_test_fails() { false; }; scope_test_fails`; each exit 2 with `internal error (exit 1)`.
  - `hook: empty active-task file is blocked with exit 2` (`.claude/active-task is empty`),
    `hook: missing spec is blocked with exit 2` (`active task spec not found: docs/tasks/999-missing.md`),
    `hook: active-task that is not a regular file is blocked with exit 2` (a directory;
    `.claude/active-task is not a regular file`).
- `task-meta:`
  - `task-meta: status rewrites only the status line`, `task-meta: log appends under the review log` (unchanged).
  - `task-meta: log appends stdin under the review log`: the same text through `-`.
  - `task-meta: log rejects text that adds a '## ' heading or a Status line and leaves the spec unchanged`: three texts
    (a `### Round 1` line followed by a `## Allowed paths` heading line and a `` - `**` `` line; a line `note` followed
    by a Status line; a lone `## Review log` line), each through a file and through stdin; each exit 2 with
    `would change the spec structure`; afterwards `cmp` shows the spec unchanged and no `.task-meta.*` file exists.
  - `task-meta: invalid status exits 2`: asserts `invalid status 'BOGUS'`.
  - `task-meta: non-spec file exits 2` (unchanged).

## Quality gates
- `scripts/agentic/scope_test.sh` exits 0 (the CI `agentic-scripts` job).
- shellcheck exits 0 for `.claude/hooks/scope-guard.sh scripts/agentic/*.sh` with each of
  `docker run --rm -v "$PWD:/mnt:ro" -w /mnt koalaman/shellcheck:<v> .claude/hooks/scope-guard.sh scripts/agentic/scope.sh scripts/agentic/task-meta.sh scripts/agentic/scope_test.sh`
  for `<v>` = `v0.9.0`, `v0.10.0`, `v0.11.0`.
- `./gradlew check` passes (no Gradle input changes; run it once to confirm).
- No new dependency (bash, git, jq, coreutils, awk, grep as today).

## Review log
<!-- Appended by the run-task orchestrator. One entry per round: verdict + numbered findings + resolution. -->

### Round 1: CHANGES_REQUESTED
The code is correct: no under-blocking and no over-blocking found in the hook, scope.sh or task-meta.sh; all 13 acceptance criteria hold; all 21 AC10 mutants (plus 3 variants) make their named case fail; scope check with both the new and the origin/main scope.sh: all within. Over-blocking probes (full Claude Code input shape, NotebookEdit, docs/backlog/** incl. `../` and unicode, CR in active-task, CLAUDE_PROJECT_DIR unset, 20 MiB input, the first Allowed path of every existing spec) all allowed. Field names: NotebookEdit `notebook_path`, Edit/Write `file_path` VERIFIED in Claude Code 2.1.330; MultiEdit has no tool definition there.
Findings (test gaps: a mutant removes the line and all 46 cases still pass):
1. No test for the dangling-symlink half of step 5 (`! -L` at scope-guard.sh:41): add a dangling symlink to the "not a regular file" case; assert exit 2, message, hint.
2. No test for `|| true` in on_exit (scope-guard.sh:25): in the unexpected-error case, also run each injected hook with stderr closed or `2>/dev/full` and assert exit 2.
3. task-meta.sh `entry` cleanup in $TMPDIR not asserted: run the meta log calls of the append, stdin and reject cases with TMPDIR at an empty directory and assert it is empty afterwards.
4. (minor) header comment lines over 120 columns (scope.sh:8 163, scope-guard.sh:2 and :6): reflow, same words; leave the spec-mandated strings.
Spec gaps (do not block): rule 3: Required tests listed only "a directory" and no closed-stderr check although §1 names both (findings 1-2 close it). Rule 1: relative-path pass-through to PreToolUse hooks UNVERIFIED (a block the agent recovers from); CI runner versions UNVERIFIED (the PR job shows them). Rule 7: Edit/Write accept an optional feature-gated `tool_input._host`; the spec does not discuss it.
Backlog suggestions: diff case with a merge-base spec > 64 KiB (pins the no-early-exit awk); decide on `_host` while a task is active; task-meta.sh:55 grep failure branch has no test.
Parallel mode: run in worktree tastebuds_api-task-015 without the scope-guard hook (user decision).

### Round 2: APPROVE
All four round 1 findings fixed: dangling-symlink fixture (X1 now fails exactly that case), `hook_nostderr` with `2>&-` in the unexpected-error case (X2 killed), `TMPDIR=$metatmp` asserted empty in the append, stdin and reject cases (X7 killed), header lines reflowed (only spec-mandated strings and the untouched scope.sh:69 stay over 120 columns). No assertion after its case's `check` line (manual and automated pass). scope_test.sh 46 ok / 0 not ok; shellcheck v0.9.0, v0.10.0, v0.11.0 rc 0; `./gradlew check` passes; AC1, AC9, AC11 hold; scope check with the new and the origin/main scope.sh: all within. All 21 AC10 mutants and the 3 variants killed. Surviving extra mutants: X3 (equivalent: on_exit turns the set -e exit 1 into 2), X4 and X14 (recorded in docs/backlog/2026-11-21-scope-test-remaining-gaps.md). Over-blocking probes repeated on the round 2 files: no change.
Spec gaps: nothing new (round 1 items stand).
Backlog: created 2026-11-21-scope-test-remaining-gaps.md, 2026-11-21-scope-guard-host-field.md and 2026-11-21-scope-guard-relative-path-unverified.md (split and corrected after the reviewer's check).
