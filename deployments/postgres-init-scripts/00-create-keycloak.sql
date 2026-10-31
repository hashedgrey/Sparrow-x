-- Docker's postgres image executes this through psql against POSTGRES_DB (default: postgres).
-- Needed by the Keycloak service in docker-compose.infrastructure.yaml.
-- Temporal auto-setup creates temporal and temporal_visibility itself; do not create them here.
-- psql's \gexec makes this safe to invoke again manually.
SELECT 'CREATE DATABASE keycloak OWNER postgres'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak')
\gexec
