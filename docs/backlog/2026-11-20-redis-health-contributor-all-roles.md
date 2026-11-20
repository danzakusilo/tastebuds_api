# Redis health contributor marks root health DOWN in every role, although the limiter fails open
- **Found during:** 001 (review round 1)
- **Location:** services/core (Boot `DataRedisHealthContributorAutoConfiguration` via `spring-boot-starter-data-redis`), services/core/src/main/resources/application.yaml
- **Severity:** medium
- **What:** The starter adds Boot's `redis` health contributor. Root `/actuator/health` reports DOWN when Redis is down,
  also for role `worker` and with `tastebuds.rate-limit.enabled=false`, where Redis is not used. The liveness/readiness
  groups do not include it, so probes are not affected today. The starter also creates an unused
  `LettuceConnectionFactory` and Redis templates with default timeouts in every role.
- **Why it matters:** A fail-open dependency can mark the pod DOWN if the Helm chart probes root health; later users of
  the Boot Redis beans (sessions, caches, D13) inherit default timeouts.
- **Suggested fix:** In the Helm chart task, keep probes on `/actuator/health/liveness|readiness` and decide on
  `management.health.redis.enabled` (or a per-role setting). Set `spring.data.redis.timeout` before sessions or caches
  use the Boot Redis beans.
