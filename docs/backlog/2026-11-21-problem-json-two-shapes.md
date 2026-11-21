# Two problem+json shapes: responses written by Spring MVC have no "type" field
- **Found during:** spec audit 2026-11-21 (task 003; checklist rules 1 and 3)
- **Location:** services/core/src/main/kotlin/dev/danya/tastebuds/core/platform/web/ProblemResponses.kt:22 (always
  writes `type`), ProblemDetailsAdvice.kt:34, :69 (`ProblemDetail.forStatusAndDetail`, `type` unset);
  docs/tasks/003-error-responses-and-body-limit.md:51 ("clients see one shape")
- **Severity:** low
- **What:** Spring Framework 7 no longer sets `ProblemDetail.type` to `about:blank` by default, and its Jackson mixin
  leaves out empty fields. Filter and security responses (401, 403, 413 with `Content-Length`, 429) carry
  `"type":"about:blank"`; MVC responses (500, 406, 413 for a streamed body, and 400/404/405/415 once reachable) have no
  `type`. So 413 has two shapes. The audit confirmed a 406 body without `type` on the running jar. No test compares the
  two writers, and the MVC statuses 400/404/405/406/415 have no test.
- **Why it matters:** RFC 9457 clients treat a missing `type` as `about:blank`, but clients that check a strict
  schema (the planned OpenAPI contract, D8) see two shapes.
- **Suggested fix:** Choose one shape (set `type` in the advice, or leave it out in `ProblemResponses`) and add one
  contract test that asserts the same fields for a filter-written problem (401) and an MVC-written problem (406 or 500).
