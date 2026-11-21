# Database roles and migrations

The app never holds DDL credentials. Migrations run in a separate `migrate` process as the schema owner
(docs/PLAN.md §8.4, §11 Phase 1).

## Roles

| Role | Kind | Privileges | Used by |
|---|---|---|---|
| `migrator` | LOGIN, owner of the database, not superuser | DDL; owns schema `core` and everything in it | Only the `migrate` command |
| `app_rw` | NOLOGIN group role | `USAGE` on `core`; `SELECT/INSERT/UPDATE/DELETE` on its tables; `USAGE/SELECT` on its sequences (V2 plus default privileges for later tables that `migrator` creates, see [Default privileges](#default-privileges)); no access to `flyway_schema_history` | Granted to the runtime login role |
| Runtime login role (e.g. `tastebuds_app`) | LOGIN, member of `app_rw`, nothing else | Whatever `app_rw` has | api and worker pods |
| `readonly` | Planned for the digests (PLAN §8.4, Phase 5) | | |

## Bootstrap (superuser, once per database)

Create the database owned by `migrator`. Run `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public` before the
first migration: pgvector is an untrusted extension, so only a superuser can create it. Then create `app_rw` and the
login role. SQL for your own Postgres:

```sql
CREATE ROLE migrator LOGIN PASSWORD '<from your secret store>';
CREATE DATABASE tastebuds OWNER migrator;
\c tastebuds
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE ROLE app_rw NOLOGIN;
CREATE ROLE tastebuds_app LOGIN PASSWORD '<from your secret store>' IN ROLE app_rw;
```

## Extension upgrades

`vector` belongs to the superuser that created it, so `migrator` cannot update it
(`must be owner of extension vector`). Migrations never alter or drop extensions; the `CREATE EXTENSION IF NOT EXISTS`
in V1 does nothing because the extension already exists. After a Postgres upgrade that ships a newer pgvector, a
superuser runs `ALTER EXTENSION vector UPDATE;` in the application database before the migrate Job of the first
release that needs the new version.

## Where each environment provisions them

- Tests and `bootTestRun`: `services/core/src/test/resources/testcontainers/postgres-init.sql`.
- Local k3d cluster: CloudNativePG (task 010).
- Your own Postgres: the SQL above.

## The `migrate` command

Run `java -jar services/core/build/libs/core-<version>.jar migrate`, or the image with argument `migrate`. `<version>`
is the Gradle project version (`version` in `gradle.properties`, for example `0.1.0-SNAPSHOT`), and
`./gradlew :services:core:bootJar` builds the jar. The exit code is 0 or 1. It writes one wide event (`work.kind=job`)
to the console. In Kubernetes a Helm pre-install/pre-upgrade Job (task 009) runs it before new pods start, and
api/worker pods set `SPRING_FLYWAY_ENABLED=false`. It fails loudly when `app_rw` is missing (V2) or Flyway is disabled.

## Rule for migrations

During a rolling deploy the previous release keeps serving against the new schema. Every migration must be backward
compatible with the release before it: expand, deploy, then contract in a later release.

## Default privileges

V2 grants `app_rw` access to the tables and sequences that exist, and runs `ALTER DEFAULT PRIVILEGES` for later ones.
Without `FOR ROLE`, Postgres records these defaults only for the role that ran V2 (`migrator`). A table or sequence in
`core` that another role creates gets no `app_rw` grants, and the app fails on it with
`permission denied for table <table>` or `permission denied for sequence <sequence>` (`<table>` and `<sequence>` are
the object names).

- Every migration runs as `migrator`, the role whose default privileges V2 recorded. A credential rotation changes its
  password; it never replaces the role. Never set `FLYWAY_USERNAME` to another login, for example a superuser, except
  to the new owner in step 2 of [Changing the schema owner](#changing-the-schema-owner).
- Never create objects in `core` by hand as another role. If it happened, a superuser grants them explicitly:
  `GRANT SELECT, INSERT, UPDATE, DELETE ON core.<table> TO app_rw;` and, for a sequence,
  `GRANT USAGE, SELECT ON SEQUENCE core.<sequence> TO app_rw;`. Changing the owner to `migrator` later does not add
  the grants.

### Changing the schema owner

`<new owner>` is the name of the role that takes over from `migrator`. Do steps 1 and 2 with no deploy between them.
The running app is not affected.

```sql
REASSIGN OWNED BY migrator TO <new owner>;
ALTER DEFAULT PRIVILEGES FOR ROLE <new owner> IN SCHEMA core GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE <new owner> IN SCHEMA core GRANT USAGE, SELECT ON SEQUENCES TO app_rw;
```

1. As a superuser, run the SQL above in the application database. `REASSIGN OWNED` moves the database, the schema and
   its objects with their grants, but not the default privileges. The two `ALTER DEFAULT PRIVILEGES` statements are
   those of V2 with `FOR ROLE <new owner>`; they record the default privileges for `<new owner>`.
2. Run the migrate command as `<new owner>` from now on: set `FLYWAY_USERNAME` and `FLYWAY_PASSWORD`, or
   `database.migratorSecret` in the Helm chart. A migrate run as `migrator` after step 1 fails with
   `permission denied for schema core`.
3. To remove `migrator`, run `DROP OWNED BY migrator;` and then `DROP ROLE migrator;` as a superuser. The old default
   privileges still belong to `migrator` and block `DROP ROLE` until then. Never run `DROP OWNED` before step 1: it
   drops the schema and its tables.

## Environment variables

| Variable | Meaning |
|---|---|
| `DATABASE_URL` | JDBC URL |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Runtime login role |
| `DATABASE_POOL_SIZE` | Connection pool size of the app |
| `FLYWAY_USERNAME`, `FLYWAY_PASSWORD` | Schema owner: `migrator`, or the new owner role after [Changing the schema owner](#changing-the-schema-owner); only the migrate process |
| `SPRING_FLYWAY_ENABLED` | `false` in api and worker pods |
| `SPRING_FLYWAY_CONNECT_RETRIES` | Migrate Job: wait for the database |

Without `FLYWAY_USERNAME` Flyway falls back to the app credentials. This works only when the app user owns the schema.
