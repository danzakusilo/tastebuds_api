# The role ArchUnit rule covers only controllers, not the worker side or functional endpoints
- **Found during:** 014 (review round 1, spec gaps)
- **Location:** services/core/src/test/kotlin/dev/danya/tastebuds/core/ArchitectureTest.kt (`controllers exist only in
  the api role`)
- **Severity:** low
- **What:** Task 014 enforces the api side of the CLAUDE.md roles contract: every `@Controller`/`@RestController`
  carries `@ConditionalOnRole(Role.API)`. Two producers are not covered: (1) the worker side — Kafka listeners and
  schedulers must carry `@ConditionalOnRole(Role.WORKER)`, but no `@KafkaListener` or `@Scheduled` class exists yet,
  so a rule now would need `allowEmptyShould`; (2) functional endpoints (`RouterFunction` beans) would serve HTTP in the
  worker role without failing the rule. None exist today.
- **Why it matters:** A listener or scheduler without the annotation would run in the api pods; a router function
  would expose endpoints in the worker pods.
- **Suggested fix:** When the first `@KafkaListener` or `@Scheduled` bean lands, add a worker-role rule with a
  self-test in `ControllerRoleRuleTest` style. If the project adopts functional endpoints, extend the api-role rule to
  `RouterFunction` bean methods.
