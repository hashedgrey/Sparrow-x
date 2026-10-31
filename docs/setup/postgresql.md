# PostgreSQL initialization

## Database layout

The infrastructure stack uses PostgreSQL 16 with a persistent `postgres-data` volume. The SQL initializer creates the SparrowX application tables in the `public` schema of the `postgres` database. It also creates the separate `keycloak` database. Temporal's auto-setup container creates its own `temporal` and `temporal_visibility` databases.

| File | Purpose |
|---|---|
| `00-create-keycloak.sql` | Create the Keycloak database if absent |
| `10-sparrowx-schema.sql` | Application schema: 19 tables and 70 explicit indexes |
| `90-verify-schema.sql` | Verify that the expected application tables exist |

Place the three SQL files in `infrastructure/postgres-init-scripts/`. The Compose bind mount points that directory to `/docker-entrypoint-initdb.d`. Schema creation and checks run automatically **only when PostgreSQL initializes an empty data directory**.

## First-time setup

From `infrastructure/docker`:

```bash
docker compose -f docker-compose.infrastructure.yaml up -d postgres
docker compose -f docker-compose.infrastructure.yaml ps postgres
docker compose -f docker-compose.infrastructure.yaml logs postgres
```

After PostgreSQL is ready, the application tables can be counted:

```bash
docker compose -f docker-compose.infrastructure.yaml exec postgres \
  psql -U postgres -d postgres -c \
  "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN ('inbox_messages','outbox_messages','documents','ingestion_jobs','document_chunks','engineers','teams','modules','repositories','internal_documents','runbooks','agentic_missions','agentic_mission_executions','agentic_step_executions','agentic_checkpoints','agentic_runtime_events','agentic_worker_leases','agentic_human_gates','engineer_team_memberships');"
```

The included `90-verify-schema.sql` performs an integrity check during initialization.

## Existing volumes

PostgreSQL does not rerun `/docker-entrypoint-initdb.d` scripts after its data directory exists. Back up any existing database and reconcile its actual schema before applying DDL manually. Applying the full schema SQL to a database containing any of its tables will fail, rather than silently alter existing objects.

To apply the schema to an existing, backed-up development database **only when none of its tables exist**, run the SQL explicitly from the Compose directory in a Bash-compatible shell:

```bash
docker compose -f docker-compose.infrastructure.yaml exec -T postgres \
  psql -X -v ON_ERROR_STOP=1 -U postgres -d postgres \
  < ../postgres-init-scripts/10-sparrowx-schema.sql
```

Run the Keycloak database creation SQL separately if needed. The schema script intentionally fails on duplicate objects and is not a migration tool.

**Do not use `docker compose down -v` on persistent data** to force initialization. Doing so removes named volumes.

## Migration ownership

Document Service enables Flyway in its base application configuration. In development environments where the SQL initializer has already created its tables, ensure that migration history and baseline behavior match the deployed schema. For a managed environment, choose a consistent migration owner rather than running competing schema creators.

The schema initializer does not populate company records, Keycloak realm resources, MinIO buckets, Neo4j graph nodes, Qdrant collections or Elasticsearch documents.
