#!/usr/bin/env bash
# Tests for scope.sh, task-meta.sh and the scope-guard hook, run in a throwaway git repo.
# Prints "ok - <name>" / "not ok - <name>" per case; exits 1 if any case failed.
# Helpers that are invoked only through run/want carry their own shellcheck directive: shellcheck cannot follow the
# indirect call (SC2317 up to shellcheck 0.10, SC2329 from 0.11).
set -uo pipefail

src="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
repo="$tmp/repo"
fakebin="$tmp/fakebin"
fakegit="$tmp/fakegit"
nojq="$tmp/nojq"
metatmp="$tmp/metatmp"  # TMPDIR of task-meta.sh: must be empty after every call
REAL_GIT="$(command -v git)"
REAL_JQ="$(command -v jq)"
export REAL_GIT REAL_JQ

export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null

failed=0
case_ok=0
spec=docs/tasks/900-x.md
spec2=docs/tasks/901-y.md
stale_hint="Only the user deletes a stale file"

# want <command...>: records a failed condition for the current case
want() { "$@" || case_ok=1; }
# check <name>: reports the current case and resets it
check() {
  if [[ "$case_ok" == 0 ]]; then echo "ok - $1"; else echo "not ok - $1"; failed=1; fi
  case_ok=0
}
# shellcheck disable=SC2317,SC2329  # called only through want
out_has() { [[ "$out" == *"$1"* ]]; }
# shellcheck disable=SC2317,SC2329  # called only through want
out_lacks() { [[ "$out" != *"$1"* ]]; }

# run <cmd...>: sets $out (stdout+stderr) and $rc
run() { out="$("$@" 2>&1)"; rc=$?; }

setup() {
  mkdir -p "$repo/scripts/agentic" "$repo/.claude/hooks" "$repo/docs/tasks" "$repo/src/a" "$repo/protected" \
    "$fakebin" "$fakegit" "$nojq"
  cp "$src/scripts/agentic/scope.sh" "$src/scripts/agentic/task-meta.sh" "$repo/scripts/agentic/"
  cp "$src/.claude/hooks/scope-guard.sh" "$repo/.claude/hooks/"
  # fake jq: fails the path extraction call of the hook (first argument -r), delegates everything else
  cat > "$fakebin/jq" <<'FAKE'
#!/usr/bin/env bash
if [[ "${1:-}" == -r ]]; then exit 5; fi
exec "$REAL_JQ" "$@"
FAKE
  # fake git: fails `git -C <dir> <args>` when the joined <args> match the glob in $FAKE_GIT_FAIL, delegates the rest
  cat > "$fakegit/git" <<'FAKE'
#!/usr/bin/env bash
# shellcheck disable=SC2053  # unquoted on purpose: glob match
if [[ -n "${FAKE_GIT_FAIL:-}" && "${1:-}" == -C && "${*:3}" == $FAKE_GIT_FAIL ]]; then exit 1; fi
exec "$REAL_GIT" "$@"
FAKE
  # no-jq PATH: every external command the hook and scope.sh use, except jq
  local tool
  for tool in bash cat tr git realpath awk sed sort env dirname basename mktemp grep; do
    ln -s "$(command -v "$tool")" "$nojq/$tool"
  done
  chmod +x "$fakebin/jq" "$fakegit/git" "$repo"/scripts/agentic/*.sh "$repo/.claude/hooks/scope-guard.sh"
  cat > "$repo/$spec" <<'SPEC'
# 900 - x

- **Status:** READY

## Goal
Test.

## Allowed paths
- `src/a/**`
- `docs/**`

## Review log
<!-- none -->
SPEC
  # second spec: only a glob that matches neither docs/backlog nor anything outside the repo on its own
  cat > "$repo/$spec2" <<'SPEC'
# 901 - y

- **Status:** READY

## Allowed paths
- `**/x/**`

## Review log
<!-- none -->
SPEC
  echo content > "$repo/protected/p.kt"  # non-empty: git skips rename detection for empty files
  git -C "$repo" init -q -b main
  git -C "$repo" add -A
  git -C "$repo" commit -q -m init
  git -C "$repo" checkout -q -b task/900-x
}

reset_repo() {
  git -C "$repo" checkout -q -f task/900-x
  git -C "$repo" reset -q --hard main
  git -C "$repo" clean -qfd
  mkdir -p "$repo/src/a"
  rm -rf "$repo/.claude/active-task"
}

# shellcheck disable=SC2317,SC2329  # called only through run
scope() { (cd "$repo" && scripts/agentic/scope.sh "$@"); }
# scope_fake_git <glob> <scope.sh args...>: git fails when its arguments after -C <dir> match <glob>
# shellcheck disable=SC2317,SC2329  # called only through run
scope_fake_git() {
  local glob="$1"; shift
  (cd "$repo" && PATH="$fakegit:$PATH" FAKE_GIT_FAIL="$glob" scripts/agentic/scope.sh "$@")
}
# shellcheck disable=SC2317,SC2329  # called only through run
meta() { (cd "$repo" && mkdir -p "$metatmp" && TMPDIR="$metatmp" scripts/agentic/task-meta.sh "$@"); }
# hook <stdin-text> [extra env assignments...]
# shellcheck disable=SC2317,SC2329  # called only through run
hook() {
  local input="$1"; shift
  (cd "$repo" && printf '%s' "$input" | env CLAUDE_PROJECT_DIR="$repo" "$@" .claude/hooks/scope-guard.sh)
}
# hook_nostderr <stdin-text>: the hook with stderr closed
# shellcheck disable=SC2317,SC2329  # called only through run
hook_nostderr() { hook "$1" 2>&-; }
edit_json() { printf '{"tool_input":{"file_path":"%s"}}' "$1"; }
notebook_json() { printf '{"tool_input":{"notebook_path":"%s"}}' "$1"; }

setup
reset_repo

# --- allowed
run scope allowed "$spec" src/a/x.kt
want [ "$rc" = 0 ]; check "allowed: matching glob is allowed"
run scope allowed "$spec" src/b/x.kt
want [ "$rc" = 1 ]; check "allowed: non-matching path is denied"
run scope allowed "$spec2" docs/backlog/x.md
want [ "$rc" = 0 ]; check "allowed: backlog is always allowed without a matching glob"
run scope allowed "$spec2" "$tmp/x/y"
want [ "$rc" = 1 ]; check "allowed: path outside the repo is denied even when a glob matches"
run scope allowed "$spec" "$spec"
want [ "$rc" = 1 ]; check "allowed: the spec itself is denied even when a glob matches"
reset_repo
# shellcheck disable=SC2016  # the backticks are literal markdown in the spec, not command substitution
printf '\n## Allowed paths\n- `**`\n' >> "$repo/$spec"
run scope allowed "$spec" src/b/x.kt
want [ "$rc" = 1 ]; check "allowed: only the first Allowed paths section counts"

# --- diff
reset_repo
echo x > "$repo/src/a/new.kt"
run scope diff "$spec" main
want [ "$rc" = 0 ]; want out_has "scope: all changes within"; check "diff: in-scope change passes"

reset_repo
echo x > "$repo/src/b.kt"
run scope diff "$spec" main
want [ "$rc" = 1 ]; want out_has "OUT OF SCOPE: src/b.kt"
check "diff: untracked out-of-scope file fails with exit 1"

reset_repo
echo y >> "$repo/protected/p.kt"
run scope diff "$spec" main
want [ "$rc" = 1 ]; want out_has "OUT OF SCOPE: protected/p.kt"
check "diff: uncommitted out-of-scope change fails with exit 1"

reset_repo
echo x > "$repo/src/b.kt"
git -C "$repo" add -A
git -C "$repo" commit -q -m change
run scope diff "$spec" main
want [ "$rc" = 1 ]; want out_has "OUT OF SCOPE: src/b.kt"
check "diff: committed out-of-scope change fails with exit 1"

reset_repo
git -C "$repo" mv protected/p.kt src/a/p.kt
run scope diff "$spec" main
want [ "$rc" = 1 ]; want out_has "OUT OF SCOPE: protected/p.kt"
check "diff: staged rename out of a protected path fails"

reset_repo
git -C "$repo" mv protected/p.kt src/a/p.kt
git -C "$repo" commit -q -m rename
run scope diff "$spec" main
want [ "$rc" = 1 ]; want out_has "OUT OF SCOPE: protected/p.kt"
check "diff: committed rename out of a protected path fails"

reset_repo
mkdir -p "$repo/docs/backlog"
echo x > "$repo/docs/backlog/n.md"
run scope diff "$spec2" main
want [ "$rc" = 0 ]; want out_has "scope: all changes within"
check "diff: backlog change passes without a matching glob"

reset_repo
run scope diff "$spec" no-such-ref
want [ "$rc" = 2 ]; want out_has "cannot determine merge base of 'no-such-ref' and HEAD"; want out_lacks "all changes within"
check "diff: unknown base ref fails with exit 2 and no success line"

reset_repo
git -C "$repo" checkout -q --orphan orphan
git -C "$repo" commit -q -m orphan
run scope diff "$spec" main
want [ "$rc" = 2 ]; want out_has "cannot determine merge base of 'main' and HEAD"; want out_lacks "all changes within"
check "diff: no merge base fails with exit 2"
git -C "$repo" checkout -q -f task/900-x
git -C "$repo" branch -q -D orphan

# a committed in-scope change is present in the three listing-failure cases
reset_repo
echo x > "$repo/src/a/new.kt"
git -C "$repo" add -A
git -C "$repo" commit -q -m change
run scope_fake_git 'diff --name-only --no-renames * HEAD' diff "$spec" main
want [ "$rc" = 2 ]; want out_lacks "all changes within"; want out_has "git failed while listing committed changes"
check "diff: failing committed listing fails with exit 2 and no success line"
run scope_fake_git 'diff --name-only --no-renames HEAD' diff "$spec" main
want [ "$rc" = 2 ]; want out_lacks "all changes within"; want out_has "git failed while listing uncommitted changes"
check "diff: failing uncommitted listing fails with exit 2 and no success line"
run scope_fake_git 'ls-files --others --exclude-standard' diff "$spec" main
want [ "$rc" = 2 ]; want out_lacks "all changes within"; want out_has "git failed while listing untracked files"
check "diff: failing untracked listing fails with exit 2 and no success line"

reset_repo
meta status "$spec" IN_PROGRESS
run scope_fake_git 'show *' diff "$spec" main
want [ "$rc" = 2 ]; want out_lacks "all changes within"; want out_has "git failed while reading the spec at the merge base"
check "diff: failing read of the spec at the merge base fails with exit 2 and no success line"

reset_repo
meta status "$spec" IN_PROGRESS
want grep -q '^- \*\*Status:\*\* IN_PROGRESS$' "$repo/$spec"
run scope diff "$spec" main
want [ "$rc" = 0 ]; check "diff: status-only spec change passes"

reset_repo
# shellcheck disable=SC2016  # the backticks are literal markdown in the spec, not command substitution
sed -i 's|^- `src/a/\*\*`|- `src/**`|' "$repo/$spec"
run scope diff "$spec" main
want [ "$rc" = 1 ]; want out_has "Allowed paths changed"
check "diff: uncommitted allowed-paths change in the spec fails"

reset_repo
# shellcheck disable=SC2016  # the backticks are literal markdown in the spec, not command substitution
sed -i 's|^- `src/a/\*\*`|- `src/**`|' "$repo/$spec"
git -C "$repo" add -A
git -C "$repo" commit -q -m widen
run scope diff "$spec" main
want [ "$rc" = 1 ]; want out_has "Allowed paths changed"
check "diff: committed allowed-paths change in the spec fails"

# --- hook
reset_repo
run hook "$(edit_json "$repo/src/b.kt")"
want [ "$rc" = 0 ]; want [ -z "$out" ]; check "hook: no active task allows everything"
run hook 'not json{' PATH="$nojq"
want [ "$rc" = 0 ]; want [ -z "$out" ]; check "hook: no active task allows the edit without jq or valid input"

echo "$spec" > "$repo/.claude/active-task"
run hook "$(edit_json "$repo/src/a/x.kt")"
want [ "$rc" = 0 ]; want [ -z "$out" ]; check "hook: allowed path passes"
run hook "$(notebook_json "$repo/src/a/x.ipynb")"
want [ "$rc" = 0 ]; want [ -z "$out" ]; check "hook: allowed notebook_path passes"
run hook "$(edit_json "$repo/src/b.kt")"
want [ "$rc" = 2 ]; want out_has "outside the allowed paths of $spec"; want out_has "$stale_hint"
check "hook: out-of-scope path is blocked with exit 2"
run hook "$(notebook_json "$repo/src/b.ipynb")"
want [ "$rc" = 2 ]; want out_has "outside the allowed paths of $spec"; want out_has "$stale_hint"
check "hook: out-of-scope notebook_path is blocked with exit 2"
run hook "$(edit_json "$repo/$spec")"
want [ "$rc" = 2 ]; want out_has "read-only"; want out_has "$stale_hint"; check "hook: active spec is blocked with exit 2"
run hook 'not json{'
want [ "$rc" = 2 ]; want out_has "stdin is not a single JSON object"; want out_has "$stale_hint"
check "hook: malformed JSON is blocked with exit 2"
run hook "$(edit_json "$repo/src/a/x.kt") $(edit_json "$repo/src/a/y.kt")"
want [ "$rc" = 2 ]; want out_has "stdin is not a single JSON object"; want out_has "$stale_hint"
check "hook: more than one JSON value is blocked with exit 2"
run hook ''
want [ "$rc" = 2 ]; want out_has "stdin is empty"; want out_has "$stale_hint"; check "hook: empty stdin is blocked with exit 2"
run hook "$(edit_json "$repo/src/a/x.kt")" PATH="$fakebin:$PATH"
want [ "$rc" = 2 ]; want out_has "jq failed to read the tool input"; want out_has "$stale_hint"
check "hook: failing jq is blocked with exit 2"
run hook "$(edit_json "$repo/src/a/x.kt")" PATH="$nojq"
want [ "$rc" = 2 ]; want out_has "jq is not installed"; want out_has "$stale_hint"
check "hook: missing jq is blocked with exit 2 during a task"

# each input must block with the "no usable path" message
bad_inputs=(
  '{}'
  "{\"tool_input\":{\"path\":\"$repo/src/a/x.kt\"}}"
  '{"tool_input":{"file_path":"src/a/x.kt"}}'
  '{"tool_input":{"file_path":5}}'
  '{"tool_input":{"file_path":""}}'
  "{\"tool_input\":{\"file_path\":\"$repo/src/b\\u0000/../a/x.kt\"}}"
  "{\"tool_input\":{\"file_path\":\"$repo/src/a/x.kt\",\"notebook_path\":\"$repo/src/b.ipynb\"}}"
)
for bad in "${bad_inputs[@]}"; do
  run hook "$bad"
  want [ "$rc" = 2 ]; want out_has "no usable path"; want out_has "$stale_hint"
done
check "hook: input without exactly one absolute path is blocked with exit 2"

printf '#!/usr/bin/env bash\nexit 3\n' > "$repo/scripts/agentic/scope.sh"
run hook "$(edit_json "$repo/src/a/x.kt")"
want [ "$rc" = 2 ]; want out_has "scope.sh failed with exit 3"; want out_has "$stale_hint"
check "hook: failing scope.sh is blocked with exit 2"
git -C "$repo" checkout -q -- scripts/agentic/scope.sh

# lines injected right after the EXIT trap run before the active-task check, so no hint is expected
trap_line='trap on_exit EXIT'
want [ "$(grep -c -x -F "$trap_line" "$repo/.claude/hooks/scope-guard.sh")" = 1 ]
# shellcheck disable=SC2016  # the injected line must reach the hook unexpanded
for injected in ': "$scope_test_unbound_variable"' 'scope_test_fails() { false; }; scope_test_fails'; do
  awk -v ins="$injected" '{print} $0 == "trap on_exit EXIT" {print ins}' "$repo/.claude/hooks/scope-guard.sh" > "$tmp/hook.mod"
  cat "$tmp/hook.mod" > "$repo/.claude/hooks/scope-guard.sh"
  run hook "$(edit_json "$repo/src/a/x.kt")"
  want [ "$rc" = 2 ]; want out_has "internal error (exit 1)"
  # with stderr closed the message cannot be written, but the status must still be 2 (not 1, which means "allow")
  run hook_nostderr "$(edit_json "$repo/src/a/x.kt")"
  want [ "$rc" = 2 ]
  git -C "$repo" checkout -q -- .claude/hooks/scope-guard.sh
done
check "hook: an unexpected error (unbound variable, failure in a function) is blocked with exit 2"

: > "$repo/.claude/active-task"
run hook "$(edit_json "$repo/src/a/x.kt")"
want [ "$rc" = 2 ]; want out_has ".claude/active-task is empty"; want out_has "$stale_hint"
check "hook: empty active-task file is blocked with exit 2"
echo docs/tasks/999-missing.md > "$repo/.claude/active-task"
run hook "$(edit_json "$repo/src/a/x.kt")"
want [ "$rc" = 2 ]; want out_has "active task spec not found: docs/tasks/999-missing.md"; want out_has "$stale_hint"
check "hook: missing spec is blocked with exit 2"
rm -f "$repo/.claude/active-task"
mkdir "$repo/.claude/active-task"
run hook "$(edit_json "$repo/src/a/x.kt")"
want [ "$rc" = 2 ]; want out_has ".claude/active-task is not a regular file"; want out_has "$stale_hint"
rmdir "$repo/.claude/active-task"
ln -s "$repo/nowhere" "$repo/.claude/active-task"  # dangling symlink: -e is false, -L is true
run hook "$(edit_json "$repo/src/a/x.kt")"
want [ "$rc" = 2 ]; want out_has ".claude/active-task is not a regular file"; want out_has "$stale_hint"
rm -f "$repo/.claude/active-task"
check "hook: active-task that is not a regular file is blocked with exit 2"

# --- task-meta
reset_repo
cp "$repo/$spec" "$tmp/before.md"
meta status "$spec" DONE
changed="$(diff "$tmp/before.md" "$repo/$spec" | grep -c '^[<>]')"
want grep -q '^- \*\*Status:\*\* DONE$' "$repo/$spec"; want [ "$changed" = 2 ]
check "task-meta: status rewrites only the status line"

reset_repo
printf '### Round 1: APPROVE\nnone\n' > "$tmp/log.md"
meta log "$spec" "$tmp/log.md"
want [ "$(tail -n 2 "$repo/$spec")" = $'### Round 1: APPROVE\nnone' ]
want [ "$(grep -n '^## Review log' "$repo/$spec" | cut -d: -f1)" -lt "$(grep -n '^### Round 1' "$repo/$spec" | cut -d: -f1)" ]
git -C "$repo" diff --numstat -- "$spec" > "$tmp/numstat"
want grep -q $'^3\t0\t' "$tmp/numstat"
want [ -z "$(ls -A "$metatmp")" ]
check "task-meta: log appends under the review log"

reset_repo
meta log "$spec" - < "$tmp/log.md"
want [ "$(tail -n 2 "$repo/$spec")" = $'### Round 1: APPROVE\nnone' ]
git -C "$repo" diff --numstat -- "$spec" > "$tmp/numstat"
want grep -q $'^3\t0\t' "$tmp/numstat"
want [ -z "$(ls -A "$metatmp")" ]
check "task-meta: log appends stdin under the review log"

reset_repo
cp "$repo/$spec" "$tmp/before.md"
# shellcheck disable=SC2016  # the backticks are literal markdown in the log text, not command substitution
printf '### Round 1\n## Allowed paths\n- `**`\n' > "$tmp/bad1.md"
printf 'note\n- **Status:** DONE\n' > "$tmp/bad2.md"
printf '## Review log\n' > "$tmp/bad3.md"
for bad in bad1 bad2 bad3; do
  run meta log "$spec" "$tmp/$bad.md"
  want [ "$rc" = 2 ]; want out_has "would change the spec structure"
  run meta log "$spec" - < "$tmp/$bad.md"
  want [ "$rc" = 2 ]; want out_has "would change the spec structure"
done
want cmp -s "$tmp/before.md" "$repo/$spec"
want [ -z "$(compgen -G "$repo/docs/tasks/.task-meta.*")" ]
want [ -z "$(ls -A "$metatmp")" ]
check "task-meta: log rejects text that adds a '## ' heading or a Status line and leaves the spec unchanged"

reset_repo
run meta status "$spec" BOGUS
want [ "$rc" = 2 ]; want out_has "invalid status 'BOGUS'"; check "task-meta: invalid status exits 2"

reset_repo
echo '- **Status:** READY' > "$repo/src/a/x.md"
run meta status src/a/x.md DONE
want [ "$rc" = 2 ]; want out_has "not inside docs/tasks"
# a structurally valid README must still be rejected by the README/TEMPLATE rule
printf '# Tasks\n\n- **Status:** READY\n\n## Review log\n' > "$repo/docs/tasks/README.md"
run meta status docs/tasks/README.md DONE
want [ "$rc" = 2 ]; want out_has "not a task spec"
check "task-meta: non-spec file exits 2"

exit "$failed"
