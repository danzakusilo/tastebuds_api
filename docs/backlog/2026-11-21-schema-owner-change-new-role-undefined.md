# The schema-owner change does not say how to create the new owner role
- **Found during:** 013 (review round 2; checklist rule 6, create)
- **Location:** docs/deploy/DATABASE.md:73 ("Changing the schema owner")
- **Severity:** low
- **What:** The procedure starts with `REASSIGN OWNED BY migrator TO <new owner>`, but no step creates `<new owner>`
  or says what it needs: LOGIN and a password, and not a superuser (like `migrator` in "Bootstrap").
- **Why it matters:** It fails loudly (a missing role fails at `REASSIGN`; a NOLOGIN role fails at Flyway
  authentication), but an operator could create it as a superuser, which the least-privilege design forbids.
- **Suggested fix:** Add a step 0: create `<new owner>` with LOGIN, a password from the Secret, NOSUPERUSER, as in
  "Bootstrap".
