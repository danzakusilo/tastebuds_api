# Spec checklist

The planner goes through these rules before it marks a spec READY, and records the result in the spec ("Assumptions
verified", "Limits", and the design notes and required tests themselves). The reviewer checks the spec and the change
against them. Each rule comes from a gap that reached a PR; the source is in brackets.

## Rules
1. **Verify tool behaviour.** Every claim about how a tool, library or platform behaves that the design relies on
   (order, defaults, paths, exit codes, version support) is checked by running it, not recalled. Record claim and
   check under "Assumptions verified". What cannot be run during planning is marked `UNVERIFIED`; the implementer
   checks it first. This includes the spec's own check commands (greps, `jq` filters, scripts in acceptance
   criteria): run them against the current tree. [009: Helm render order; audit 2026-11-21: 010 AC3 grep]
2. **Define every placeholder.** Notation in docs and examples (`<release>`, `<n>`, `<env>`) is defined once, with the
   rule that produces the real value. [009: README resource names]
3. **Same contract, same tests.** When several parts share a contract (workloads, roles, endpoints, implementations of
   one interface), the required tests cover the contract for each part, not only the first one. [009: worker probes]
4. **Limits that wrap each other.** Every timeout, deadline, retry budget and size limit the change sets or depends on
   goes under "Limits", with the limit that wraps it. The outer one must be larger (caller timeout > callee deadline >
   retries × backoff). Include the defaults of tools that wrap the change (Helm `--timeout`, client timeouts,
   termination grace periods). The outer limit must also stop the inner work, or the spec says what keeps running
   when it fires (a killed pod does not cancel a Postgres lock wait). [009: Helm waits 5 min, Job deadline 600 s;
   audit 2026-11-21: 002/008 migration lock wait outlives the Job]
5. **A "must fail" check names the reason.** Every negative test or check asserts why it failed (message, status, exit
   code), so an unrelated crash cannot make it pass. [009: lint.sh accepts any non-zero exit]
6. **Whole lifecycle.** For every object the change creates (resource, row, file, key, Job, Secret), the spec states what
   happens on create, update, failure and removal; user docs say it where it surprises. [009: uninstall keeps the Job]
7. **What a client can make it do.** For every fallback that relaxes a control (fail open, default allow) and every
   library step that handles untrusted input, list what triggers it and show that a client cannot trigger it with
   load, concurrency or crafted input; side effects (DNS, I/O, logging) count. [audit 2026-11-21: 001 compare-and-swap
   contention turns into fail open; 004 `RemoteIpValve` resolves host names from `X-Forwarded-For`]

## Candidates (seen once)
<!-- A spec-origin gap from a review or a /brief-pr that no rule above covers. One line: pattern, task, example.
     Promote it to a rule when the pattern reaches a second PR. -->
- Partial backlog closure: a spec that closes part of a backlog item lists the file in Allowed paths and states the
  edit. [008: `2026-11-15-app-rw-role-provisioning.md` left stale; 009 §12 did it right]
- Logged library messages: when a spec logs a third-party exception message, it checks every kind of data the message
  can carry (credentials, server DETAIL with row values, statement text). [008: pgjdbc DETAIL in the migrate log]
- In-band configuration: free text written into a file that another tool parses for structure is checked, or the
  structure is checked again after the write. [005: review log text read as Allowed paths]
- Guard allow paths: a guard spec lists its allow cases (exit 0) and the order of its checks, each with a reason and a
  test. [005: hook allows input without a path; blocks on missing jq before it checks for an active task]
- Producers behind a contract claim: "every error is X" or "known gaps: A, B" lists every producer (advice, security
  handlers, filters, firewall, `/error`, container error page). [003: firewall 400 missing]
- Failure phases: a "dependency does not answer" test names the phase it covers (connect vs. open connection) and both
  are covered. [001: only the handshake timeout is tested]
- Narrow fix before a weakening fallback: a fallback that turns a control off comes after the narrow fix. [011: LGTM
  fallback drops the read-only root where one `emptyDir` is enough]

## Maintenance
- Change this file on its own branch, never on a task branch (the task-scope CI check would fail).
- A new pattern starts as a candidate; it becomes a rule on its second appearance. Keep the rules to about ten.
- At the end of each PLAN phase, remove rules and candidates that no longer catch anything.
