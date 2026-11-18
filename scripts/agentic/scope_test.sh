#!/usr/bin/env bash
# Tests for scope.sh, task-meta.sh and the scope-guard hook, run in a throwaway git repo.
# Prints "ok - <name>" / "not ok - <name>" per case; exits 1 if any case failed.
# The helper functions are invoked indirectly through run/want, which shellcheck cannot follow
# (SC2317 up to shellcheck 0.10, SC2329 from 0.11).
# shellcheck disable=SC2317,SC2329
set -uo pipefail

src="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
repo="$tmp/repo"
fakebin="$tmp/fakebin"
fakegit="$tmp/fakegit"
real_git="$(command -v git)"

export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null

failed=0
case_ok=0
spec=docs/tasks/900-x.md

# want <command...>: records a failed condition for the current case
want() { "$@" || case_ok=1; }
# check <name>: reports the current case and resets it
check() {
  if [[ "$case_ok" == 0 ]]; then echo "ok - $1"; else echo "not ok - $1"; failed=1; fi
  case_ok=0
}
out_has() { [[ "$out" == *"$1"* ]]; }
out_lacks() { [[ "$out" != *"$1"* ]]; }

# run <cmd...>: sets $out (stdout+stderr) and $rc
run() { out="$("$@" 2>&1)"; rc=$?; }

setup() {
  mkdir -p "$repo/scripts/agentic" "$repo/.claude/hooks" "$repo/docs/tasks" "$repo/src/a" "$fakebin" "$fakegit"
  cp "$src/scripts/agentic/scope.sh" "$src/scripts/agentic/task-meta.sh" "$repo/scripts/agentic/"
  cp "$src/.claude/hooks/scope-guard.sh" "$repo/.claude/hooks/"
  printf '#!/usr/bin/env bash\nexit 1\n' > "$fakebin/jq"
  # fake git: fails only `git -C <dir> diff --name-only <merge-base> HEAD`, delegates everything else
  cat > "$fakegit/git" <<FAKE
#!/usr/bin/env bash
if [[ \$# == 6 && "\$3" == diff && "\$4" == --name-only && "\$6" == HEAD ]]; then exit 1; fi
exec "$real_git" "\$@"
FAKE
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
  rm -f "$repo/.claude/active-task"
}

scope() { (cd "$repo" && scripts/agentic/scope.sh "$@"); }
scope_fake_git() { (cd "$repo" && PATH="$fakegit:$PATH" scripts/agentic/scope.sh "$@"); }
meta() { (cd "$repo" && scripts/agentic/task-meta.sh "$@"); }
hook() { # hook <stdin-text> [extra env assignments...]
  local input="$1"; shift
  (cd "$repo" && printf '%s' "$input" | env CLAUDE_PROJECT_DIR="$repo" "$@" .claude/hooks/scope-guard.sh)
}
edit_json() { printf '{"tool_input":{"file_path":"%s"}}' "$1"; }

setup
reset_repo

# --- allowed
run scope allowed "$spec" src/a/x.kt
want [ "$rc" = 0 ]; check "allowed: matching glob is allowed"
run scope allowed "$spec" src/b/x.kt
want [ "$rc" = 1 ]; check "allowed: non-matching path is denied"
run scope allowed "$spec" docs/backlog/x.md
want [ "$rc" = 0 ]; check "allowed: backlog is always allowed"
run scope allowed "$spec" /etc/passwd
want [ "$rc" = 1 ]; check "allowed: path outside the repo is denied"
run scope allowed "$spec" "$spec"
want [ "$rc" = 1 ]; check "allowed: the spec itself is denied even when a glob matches"

# --- diff
reset_repo
echo x > "$repo/src/a/new.kt"
run scope diff "$spec" main
want [ "$rc" = 0 ]; check "diff: in-scope change passes"

reset_repo
echo x > "$repo/src/b.kt"
run scope diff "$spec" main
want [ "$rc" = 1 ]; want out_has "OUT OF SCOPE: src/b.kt"; check "diff: out-of-scope change fails with exit 1"

reset_repo
run scope diff "$spec" no-such-ref
want [ "$rc" = 2 ]; want out_lacks "all changes within"
check "diff: unknown base ref fails with exit 2 and no success line"

reset_repo
git -C "$repo" checkout -q --orphan orphan
git -C "$repo" commit -q -m orphan
run scope diff "$spec" main
want [ "$rc" = 2 ]; want out_has "merge base"; want out_lacks "all changes within"
check "diff: no merge base fails with exit 2"
git -C "$repo" checkout -q -f task/900-x
git -C "$repo" branch -q -D orphan

reset_repo
echo x > "$repo/src/a/new.kt"
git -C "$repo" add -A
git -C "$repo" commit -q -m change
run scope_fake_git diff "$spec" main
want [ "$rc" = 2 ]; want out_lacks "all changes within"; want out_has "git failed while listing changes"
check "diff: failing git diff fails with exit 2 and no success line"

reset_repo
meta status "$spec" IN_PROGRESS
want grep -q '^- \*\*Status:\*\* IN_PROGRESS$' "$repo/$spec"
run scope diff "$spec" main
want [ "$rc" = 0 ]; check "diff: status-only spec change passes"

reset_repo
# shellcheck disable=SC2016  # the backticks are literal markdown in the spec, not command substitution
sed -i 's|^- `src/a/\*\*`|- `src/**`|' "$repo/$spec"
run scope diff "$spec" main
want [ "$rc" = 1 ]; want out_has "Allowed paths changed"; check "diff: allowed-paths change in the spec fails"

# --- hook
reset_repo
run hook "$(edit_json "$repo/src/b.kt")"
want [ "$rc" = 0 ]; check "hook: no active task allows everything"

echo "$spec" > "$repo/.claude/active-task"
run hook "$(edit_json "$repo/src/a/x.kt")"
want [ "$rc" = 0 ]; check "hook: allowed path passes"
run hook "$(edit_json "$repo/src/b.kt")"
want [ "$rc" = 2 ]; check "hook: out-of-scope path is blocked with exit 2"
run hook "$(edit_json "$repo/$spec")"
want [ "$rc" = 2 ]; want out_has "read-only"; check "hook: active spec is blocked with exit 2"
run hook 'not json{'
want [ "$rc" = 2 ]; check "hook: malformed JSON is blocked with exit 2"
run hook ''
want [ "$rc" = 2 ]; check "hook: empty stdin is blocked with exit 2"
run hook "$(edit_json "$repo/src/a/x.kt")" PATH="$fakebin:$PATH"
want [ "$rc" = 2 ]; check "hook: failing jq is blocked with exit 2"
: > "$repo/.claude/active-task"
run hook "$(edit_json "$repo/src/a/x.kt")"
want [ "$rc" = 2 ]; check "hook: empty active-task file is blocked with exit 2"
echo docs/tasks/999-missing.md > "$repo/.claude/active-task"
run hook "$(edit_json "$repo/src/a/x.kt")"
want [ "$rc" = 2 ]; check "hook: missing spec is blocked with exit 2"
rm -f "$repo/.claude/active-task"

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
check "task-meta: log appends under the review log"

reset_repo
run meta status "$spec" BOGUS
want [ "$rc" = 2 ]; check "task-meta: invalid status exits 2"

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
