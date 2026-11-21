# The ingress body limit must not be below the app's 1 MB request body cap
- **Found during:** spec audit 2026-11-21 (task 003; checklist rule 4)
- **Location:** services/core/src/main/resources/application.yaml:111 (body cap 1 MB), :43, :54-55 (Tomcat limits);
  future Traefik ingress (PLAN D21)
- **Severity:** low
- **What:** The 1 MB cap is wrapped by Tomcat's swallow limit (2 MB, correct today), the form-post limit (1 MB) and
  multipart defaults (2026-11-18-multipart-limits-exceed-body-cap.md). A future Traefik body limit below 1 MB would
  answer with Traefik's own 413, which is not problem+json. The audit confirmed that 3 MB and 50 MB bodies with
  `Content-Length` receive the full 413 problem body from the app today. Traefik defaults were not verified.
- **Why it matters:** Clients would see two 413 formats depending on size.
- **Suggested fix:** In the ingress task, add "ingress body limit ≥ app cap" to Limits and verify Traefik's default.
