# The schema-owner change SQL block does not run in one transaction
- **Found during:** 013 (review round 2)
- **Location:** docs/deploy/DATABASE.md:73 ("Changing the schema owner", step 1)
- **Severity:** low
- **What:** Step 1 is a block of three statements (`REASSIGN OWNED` and the two `ALTER DEFAULT PRIVILEGES ... FOR ROLE
  <new owner>`). Run statement by statement, a typo in one `<new owner>` moves the ownership but leaves a default
  privilege missing. Verified in review: with `BEGIN;` ... `COMMIT;` (or `psql --single-transaction`) the block works
  and an error rolls everything back.
- **Why it matters:** A half-applied block makes the next table the new owner creates unusable for the app
  (`permission denied for table`), found only after a deploy.
- **Suggested fix:** Wrap the block in `BEGIN;` ... `COMMIT;` or tell the operator to run it with
  `psql --single-transaction`.
