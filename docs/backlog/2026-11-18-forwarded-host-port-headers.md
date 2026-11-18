# X-Forwarded-Host / X-Forwarded-Port are honoured from internal proxies by Boot default
- **Found during:** 004 (run-task round 1 review)
- **Location:** services/core/src/main/resources/application.yaml (`server.tomcat.remoteip`)
- **Severity:** low
- **What:** Boot 4.1 defaults `server.tomcat.remoteip.host-header=X-Forwarded-Host` and `port-header=X-Forwarded-Port`.
  From a trusted peer, `X-Forwarded-Host: evil.example` + `X-Forwarded-Port: 8443` yields `serverName=evil.example`,
  `serverPort=8443`, `requestURL=https://evil.example:8443/...` (reviewer probe). Nothing in the app needs these yet.
- **Why it matters:** Once code builds absolute URLs or `Location` headers (Phase 1+), a misconfigured ingress that passes
  client values through enables host-header poisoning.
- **Suggested fix:** Decide whether to honour them. If not, blank `host-header` and `port-header` and pin with a test
  before any absolute-URL code lands; if yes, keep the operator checklist requirement that the ingress overwrites them.
