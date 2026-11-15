# ArchUnit SQL rule misses string-templated SQL passed to safe-looking APIs
- **Found during:** ad hoc (external review of Phase 0)
- **Location:** services/core/src/test (ArchUnit rules banning java.sql.Statement)
- **Severity:** security
- **What:** The rule bans `java.sql.Statement`, but `JdbcClient.sql("... $input")`, `JdbcTemplate.query(String)`,
  jOOQ `DSL.sql/field/condition(String)` and `.execute(String)` accept a string built by Kotlin templates and are
  not caught. SECURITY.md's "parameterized queries only" therefore overstates what is enforced.
- **Why it matters:** SQL injection is possible without tripping the build.
- **Suggested fix:** Semgrep (already planned in PLAN §8.4) rule flagging string templates/concatenation flowing into
  those APIs' first argument; and/or ArchUnit rule restricting plain-SQL jOOQ/JdbcClient entry points to an allow-listed
  package. Bytecode-level ArchUnit cannot see Kotlin templates directly, so Semgrep (or detekt custom rule) is the real fix.
