# Spec checklist

The planner goes through these rules before it marks a spec READY, and records the result in the spec ("Assumptions
verified", "Limits", and the design notes and required tests themselves). The reviewer checks the spec and the change
against them. Each rule comes from a gap that reached a PR; the source is in brackets.

## Rules
1. **Verify tool behaviour.** Every claim about how a tool, library or platform behaves that the design relies on
   (order, defaults, paths, exit codes, version support) is checked by running it, not recalled. Record claim and
   check under "Assumptions verified". What cannot be run during planning is marked `UNVERIFIED`; the implementer
   checks it first. [009: Helm render order]
2. **Define every placeholder.** Notation in docs and examples (`<release>`, `<n>`, `<env>`) is defined once, with the
   rule that produces the real value. [009: README resource names]
3. **Same contract, same tests.** When several parts share a contract (workloads, roles, endpoints, implementations of
   one interface), the required tests cover the contract for each part, not only the first one. [009: worker probes]
4. **Limits that wrap each other.** Every timeout, deadline, retry budget and size limit the change sets or depends on
   goes under "Limits", with the limit that wraps it. The outer one must be larger (caller timeout > callee deadline >
   retries × backoff). Include the defaults of tools that wrap the change (Helm `--timeout`, client timeouts,
   termination grace periods). [009: Helm waits 5 min, Job deadline 600 s]
5. **A "must fail" check names the reason.** Every negative test or check asserts why it failed (message, status, exit
   code), so an unrelated crash cannot make it pass. [009: lint.sh accepts any non-zero exit]
6. **Whole lifecycle.** For every object the change creates (resource, row, file, key, Job, Secret), the spec states what
   happens on create, update, failure and removal; user docs say it where it surprises. [009: uninstall keeps the Job]

## Candidates (seen once)
<!-- A spec-origin gap from a review or a /brief-pr that no rule above covers. One line: pattern, task, example.
     Promote it to a rule when the pattern reaches a second PR. -->

## Maintenance
- Change this file on its own branch, never on a task branch (the task-scope CI check would fail).
- A new pattern starts as a candidate; it becomes a rule on its second appearance. Keep the rules to about ten.
- At the end of each PLAN phase, remove rules and candidates that no longer catch anything.
