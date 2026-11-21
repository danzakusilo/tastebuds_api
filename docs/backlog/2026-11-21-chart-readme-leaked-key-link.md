# The chart README's "Rotating a Secret" does not point to the leaked-key procedure
- **Found during:** 013 (review rounds 1 and 2)
- **Location:** deploy/helm/tastebuds-core/README.md:71-74
- **Severity:** low
- **What:** Task 013 adds a "Leaked key" section to docs/security/ANON_SESSIONS.md: remove the leaked key, move the
  remaining keys down so the indices stay contiguous from 0, add a new key only when none remains, then deploy. The
  chart README links ANON_SESSIONS.md as a whole ("Signing keys follow ..."), but not the `#leaked-key` section, and
  its first line says only "edit the Secret, then `kubectl rollout restart`". It also does not say that an in-place
  Secret edit affects every pod that restarts, not only the rollout.
- **Why it matters:** An operator in an incident who stops at the "edit, then restart" line can leave a gap in the key
  indices (every new pod fails at startup) or miss that old pods keep accepting the leaked key until they are replaced.
- **Suggested fix:** Link `ANON_SESSIONS.md#leaked-key` from "Rotating a Secret" and add the restart note.
