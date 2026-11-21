# Database roles and migrations

The app never holds DDL credentials. Migrations run in a separate `migrate` process as the schema owner
(docs/PLAN.md §8.4, §11 Phase 1).

## Roles

| Role | Kind | Privileges | Used by |
|---|---|---|---|
| `migrator` | LOGIN, owner of the database, not superuser | DDL; owns schema `core` and everything in it | Only the `migrate` command |
| `app_rw` | NOLOGIN group role | `USAGE` on `core`; `SELECT/INSERT/UPDATE/DELETE` on its tables; `USAGE/SELECT` on its sequences (V2 plus default privileges for later tables); no access to `flyway_schema_history` | Granted to the runtime login role |
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

## Where each environment provisions them

- Tests and `bootTestRun`: `services/core/src/test/resources/testcontainers/postgres-init.sql`.
- Local k3d cluster: CloudNativePG (task 010).
- Your own Postgres: the SQL above.

## The `migrate` command

Run `java -jar services/core/build/libs/core-<version>.jar migrate`, or the image with argument `migrate`. The exit
code is 0 or 1. It writes one wide event (`work.kind=job`) to the console. In Kubernetes a Helm pre-install/pre-upgrade
Job (task 009) runs it before new pods start, and api/worker pods set `SPRING_FLYWAY_ENABLED=false`. It fails loudly
when `app_rw` is missing (V2) or Flyway is disabled.

## Rule for migrations

During a rolling deploy the previous release keeps serving against the new schema. Every migration must be backward
compatible with the release before it: expand, deploy, then contract in a later release.

## Environment variables

| Variable | Meaning |
|---|---|
| `DATABASE_URL` | JDBC URL |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Runtime login role |
| `DATABASE_POOL_SIZE` | Connection pool size of the app |
| `FLYWAY_USERNAME`, `FLYWAY_PASSWORD` | Schema owner (`migrator`); only the migrate process |
| `SPRING_FLYWAY_ENABLED` | `false` in api and worker pods |
| `SPRING_FLYWAY_CONNECT_RETRIES` | Migrate Job: wait for the database |

Without `FLYWAY_USERNAME` Flyway falls back to the app credentials. This works only when the app user owns the schema.
