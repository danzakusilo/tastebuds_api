# Boot Redis beans use default timeouts (set spring.data.redis.timeout before sessions or caches use them)
- **Found during:** 001 (review round 1)
- **Location:** services/core (Boot Redis connection auto-configuration, `spring.data.redis.*`, via `spring-boot-starter-data-redis`), services/core/src/main/resources/application.yaml
- **Severity:** medium
- **What:** Resolved by task 009: the Helm probes use the liveness/readiness health groups and the worker sets
  `MANAGEMENT_HEALTH_REDIS_ENABLED=false`. Remaining: the starter creates an unused `LettuceConnectionFactory` and
  Redis templates with default timeouts in every role.
- **Why it matters:** Later users of the Boot Redis beans (sessions, caches, D13) inherit default timeouts.
- **Suggested fix:** Set `spring.data.redis.timeout` before sessions or caches use the Boot Redis beans.
