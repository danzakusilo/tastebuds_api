# Wide event is emitted before async/streaming responses complete
- **Found during:** ad hoc (external review of Phase 0)
- **Location:** libs/observability/src/main/kotlin/dev/danya/tastebuds/observability/WideEventFilter.kt
- **Severity:** medium
- **What:** The filter emits the wide event when `doFilter` returns. For SSE (`SseEmitter`), `StreamingResponseBody`
  or other async MVC responses, that happens when the request goes async, not when the response completes, so
  status, duration and fields added later are wrong or missing.
- **Why it matters:** D8 plans SSE for the live feed; its wide events would be misleading, breaking the
  "one accurate event per request" contract.
- **Suggested fix:** If `request.isAsyncStarted`, register an `AsyncListener` (onComplete/onError/onTimeout) and emit
  there; keep the event bound for async dispatches (`shouldNotFilterAsyncDispatch = false` or explicit handling).
  Test with an `SseEmitter` endpoint.
