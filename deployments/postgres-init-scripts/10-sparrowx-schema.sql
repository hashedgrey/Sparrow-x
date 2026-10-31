-- Source: user-supplied SparrowX DDL, 2026-10-31.
-- Schema in PostgreSQL database `postgres`, schema `public`.
-- Kept in the supplied order to honor foreign-key dependencies.
-- Executes only for a NEW postgres-data volume under the official PostgreSQL image.
-- IMPORTANT: Not a Flyway migration and not safe to rerun on an existing schema.
-- All statements are wrapped in one transaction for an all-or-nothing initial schema creation.
BEGIN;

create table public.inbox_messages
(
    id           uuid         not null
        primary key,
    event_type   varchar(255) not null,
    processed_at timestamp    not null
);

alter table public.inbox_messages
    owner to postgres;

create index idx_inbox_processed_at
    on public.inbox_messages (processed_at);

create table public.outbox_messages
(
    id             uuid                     not null
        primary key,
    aggregate_type varchar(255)             not null,
    event_type     varchar(255)             not null,
    topic          varchar(255)             not null,
    message_key    varchar(255),
    payload        text                     not null,
    headers        text,
    status         varchar(50)              not null,
    created_at     timestamp with time zone not null,
    processed_at   timestamp with time zone,
    attempts       integer default 0        not null,
    next_retry_at  timestamp with time zone,
    last_error     text,
    version        bigint
);

alter table public.outbox_messages
    owner to postgres;

create index idx_outbox_status_created
    on public.outbox_messages (status, created_at);

create index idx_outbox_retry
    on public.outbox_messages (status, next_retry_at);

create table public.documents
(
    document_id        varchar(64)              not null
        primary key,
    tenant_id          varchar(255)             not null,
    project_id         varchar(255),
    team_id            varchar(255),
    title              varchar(255),
    file_name          varchar(255)             not null,
    mime_type          varchar(255)             not null,
    size_bytes         bigint                   not null,
    object_key         varchar(255),
    content_hash       varchar(255),
    status             varchar(64)              not null,
    created_at         timestamp with time zone not null,
    updated_at         timestamp with time zone not null,
    created_by_user_id varchar(255)             not null
);

alter table public.documents
    owner to postgres;

create index idx_documents_tenant_id
    on public.documents (tenant_id);

create index idx_documents_tenant_project
    on public.documents (tenant_id, project_id);

create index idx_documents_tenant_team
    on public.documents (tenant_id, team_id);

create index idx_documents_status
    on public.documents (status);

create index idx_documents_created_at
    on public.documents (created_at);

create table public.ingestion_jobs
(
    ingestion_job_id varchar(64)              not null
        primary key,
    document_id      varchar(64)              not null
        constraint fk_ingestion_jobs_document
            references public.documents
            on delete cascade,
    status           varchar(64)              not null,
    failure_reason   text,
    chunks_created   integer default 0        not null,
    chunks_indexed   integer default 0        not null,
    created_at       timestamp with time zone not null,
    completed_at     timestamp with time zone,
    tenant_id        varchar(255)             not null
);

alter table public.ingestion_jobs
    owner to postgres;

create index idx_ingestion_jobs_document_id
    on public.ingestion_jobs (document_id);

create index idx_ingestion_jobs_status
    on public.ingestion_jobs (status);

create index idx_ingestion_jobs_created_at
    on public.ingestion_jobs (created_at);

create index idx_ingestion_jobs_tenant_job
    on public.ingestion_jobs (tenant_id, ingestion_job_id);

create index idx_ingestion_jobs_tenant_status
    on public.ingestion_jobs (tenant_id, status);

create table public.document_chunks
(
    id                 bigserial
        primary key,
    chunk_id           varchar(64)              not null
        unique,
    document_id        varchar(64)              not null
        constraint fk_document_chunks_document
            references public.documents
            on delete cascade,
    tenant_id          varchar(64)              not null,
    project_id         varchar(64),
    team_id            varchar(64),
    chunk_index        integer                  not null,
    chunk_text         text                     not null,
    page_start         integer default 0        not null,
    page_end           integer default 0        not null,
    content_hash       varchar(128)             not null,
    token_count        integer,
    embedding_id       varchar(128),
    search_document_id varchar(128),
    created_at         timestamp with time zone not null,
    constraint uq_document_chunks_document_index
        unique (document_id, chunk_index)
);

alter table public.document_chunks
    owner to postgres;

create index idx_document_chunks_document_id
    on public.document_chunks (document_id);

create index idx_document_chunks_tenant_document
    on public.document_chunks (tenant_id, document_id);

create index idx_document_chunks_tenant_project
    on public.document_chunks (tenant_id, project_id);

create index idx_document_chunks_tenant_team
    on public.document_chunks (tenant_id, team_id);

create index idx_document_chunks_embedding_id
    on public.document_chunks (embedding_id);

create index idx_document_chunks_search_document_id
    on public.document_chunks (search_document_id);

create index idx_document_chunks_content_hash
    on public.document_chunks (content_hash);

create table public.engineers
(
    engineer_id varchar(64)              not null
        primary key,
    tenant_id   varchar(128)             not null,
    full_name   varchar(255)             not null,
    email       varchar(320)             not null,
    role        varchar(64)              not null,
    created_at  timestamp with time zone not null,
    updated_at  timestamp with time zone not null
);

alter table public.engineers
    owner to postgres;

create unique index idx_engineers_tenant_email
    on public.engineers (tenant_id, email);

create index idx_engineers_tenant_role
    on public.engineers (tenant_id, role);

create table public.teams
(
    team_id     varchar(64)              not null
        primary key,
    tenant_id   varchar(128)             not null,
    name        varchar(255)             not null,
    slug        varchar(255)             not null,
    description text default ''::text    not null,
    created_at  timestamp with time zone not null,
    updated_at  timestamp with time zone not null
);

alter table public.teams
    owner to postgres;

create unique index idx_teams_tenant_slug
    on public.teams (tenant_id, slug);

create index idx_teams_tenant_name
    on public.teams (tenant_id, name);

create table public.modules
(
    module_id      varchar(64)              not null
        primary key,
    tenant_id      varchar(128)             not null,
    name           varchar(255)             not null,
    slug           varchar(255)             not null,
    description    text default ''::text    not null,
    owning_team_id varchar(64)              not null
        constraint fk_modules_owning_team
            references public.teams,
    created_at     timestamp with time zone not null,
    updated_at     timestamp with time zone not null
);

alter table public.modules
    owner to postgres;

create unique index idx_modules_tenant_slug
    on public.modules (tenant_id, slug);

create index idx_modules_tenant_owning_team
    on public.modules (tenant_id, owning_team_id);

create index idx_intsvc_modules_tenant_owner
    on public.modules (tenant_id, owning_team_id);

create table public.repositories
(
    repository_id varchar(64)              not null
        primary key,
    tenant_id     varchar(128)             not null,
    name          varchar(255)             not null,
    url           text                     not null,
    module_id     varchar(64)              not null
        constraint fk_repositories_module
            references public.modules,
    created_at    timestamp with time zone not null,
    updated_at    timestamp with time zone not null
);

alter table public.repositories
    owner to postgres;

create index idx_repositories_tenant_name
    on public.repositories (tenant_id, name);

create index idx_repositories_tenant_module
    on public.repositories (tenant_id, module_id);

create unique index idx_repositories_tenant_url
    on public.repositories (tenant_id, url);

create index idx_intsvc_repository_tenant_module
    on public.repositories (tenant_id, module_id);

create table public.internal_documents
(
    document_id        varchar(64)              not null
        primary key,
    tenant_id          varchar(128)             not null,
    title              varchar(255)             not null,
    slug               varchar(255)             not null,
    summary            text default ''::text    not null,
    module_id          varchar(64)              not null
        constraint fk_internal_documents_module
            references public.modules,
    repository_id      varchar(64)              not null
        constraint fk_internal_documents_repository
            references public.repositories,
    external_ref       text                     not null,
    created_at         timestamp with time zone not null,
    updated_at         timestamp with time zone not null,
    docsvc_document_id varchar(64)
);

alter table public.internal_documents
    owner to postgres;

create unique index idx_internal_documents_tenant_slug
    on public.internal_documents (tenant_id, slug);

create index idx_internal_documents_tenant_module
    on public.internal_documents (tenant_id, module_id);

create index idx_internal_documents_tenant_repository
    on public.internal_documents (tenant_id, repository_id);

create index idx_internal_documents_tenant_external_ref
    on public.internal_documents (tenant_id, external_ref);

create index idx_intsvc_doc_tenant_module
    on public.internal_documents (tenant_id, module_id);

create index idx_intsvc_doc_tenant_repo
    on public.internal_documents (tenant_id, repository_id);

create index idx_internal_documents_docsvc_ref
    on public.internal_documents (tenant_id, docsvc_document_id)
    where (docsvc_document_id IS NOT NULL);

create table public.runbooks
(
    runbook_id  varchar(64)              not null
        primary key,
    tenant_id   varchar(128)             not null,
    title       varchar(255)             not null,
    slug        varchar(255)             not null,
    summary     text default ''::text    not null,
    module_id   varchar(64)              not null
        constraint fk_runbooks_module
            references public.modules,
    document_id varchar(64)              not null
        constraint fk_runbooks_document
            references public.internal_documents,
    created_at  timestamp with time zone not null,
    updated_at  timestamp with time zone not null
);

alter table public.runbooks
    owner to postgres;

create unique index idx_runbooks_tenant_slug
    on public.runbooks (tenant_id, slug);

create index idx_runbooks_tenant_module
    on public.runbooks (tenant_id, module_id);

create index idx_runbooks_tenant_document
    on public.runbooks (tenant_id, document_id);

create index idx_intsvc_runbook_tenant_module
    on public.runbooks (tenant_id, module_id);

create index idx_intsvc_runbook_tenant_document
    on public.runbooks (tenant_id, document_id);

create table public.agentic_missions
(
    mission_id      varchar(64)                not null
        primary key,
    request_id      varchar(128)               not null,
    tenant_id       varchar(128)               not null,
    user_id         varchar(128)               not null,
    username        varchar(255),
    project_id      varchar(128),
    team_id         varchar(128),
    trace_id        varchar(128),
    caller_service  varchar(128),
    session_id      varchar(128),
    conversation_id varchar(128),
    client_channel  varchar(128),
    query           text                       not null,
    status          varchar(64)                not null,
    selected_path   varchar(128)               not null,
    submitted_at    timestamp with time zone   not null,
    terminal_at     timestamp with time zone,
    metadata        jsonb  default '{}'::jsonb not null,
    updated_at      timestamp with time zone   not null,
    row_version     bigint default 0           not null
);

alter table public.agentic_missions
    owner to postgres;

create unique index idx_agentic_missions_tenant_request
    on public.agentic_missions (tenant_id, request_id);

create index idx_agentic_missions_tenant_status
    on public.agentic_missions (tenant_id, status);

create index idx_agentic_missions_tenant_user
    on public.agentic_missions (tenant_id, user_id);

create table public.agentic_mission_executions
(
    execution_id    varchar(64)              not null
        primary key,
    tenant_id       varchar(128)             not null,
    mission_id      varchar(64)              not null
        constraint fk_agentic_execution_mission
            references public.agentic_missions,
    status          varchar(64)              not null,
    current_step_id varchar(64),
    version         bigint                   not null,
    created_at      timestamp with time zone not null,
    updated_at      timestamp with time zone not null,
    started_at      timestamp with time zone,
    completed_at    timestamp with time zone
);

alter table public.agentic_mission_executions
    owner to postgres;

create unique index idx_agentic_execution_tenant_mission
    on public.agentic_mission_executions (tenant_id, mission_id);

create index idx_agentic_execution_status
    on public.agentic_mission_executions (status);

create index idx_agentic_execution_recoverable
    on public.agentic_mission_executions (status, updated_at);

create table public.agentic_step_executions
(
    step_id               varchar(64)               not null
        primary key,
    tenant_id             varchar(128)              not null,
    mission_id            varchar(64)               not null
        constraint fk_agentic_steps_mission
            references public.agentic_missions,
    kind                  varchar(64)               not null,
    status                varchar(64)               not null,
    attempt               integer                   not null
        constraint chk_agentic_steps_attempt_non_negative
            check (attempt >= 0),
    max_attempts          integer                   not null
        constraint chk_agentic_steps_max_attempts_positive
            check (max_attempts > 0),
    input_checkpoint_ref  varchar(64),
    output_checkpoint_ref varchar(64),
    failure_code          varchar(128),
    failure_message       text,
    metadata              jsonb default '{}'::jsonb not null,
    created_at            timestamp with time zone  not null,
    updated_at            timestamp with time zone  not null,
    leased_at             timestamp with time zone,
    started_at            timestamp with time zone,
    completed_at          timestamp with time zone
);

alter table public.agentic_step_executions
    owner to postgres;

create index idx_agentic_steps_tenant_mission
    on public.agentic_step_executions (tenant_id, mission_id);

create index idx_agentic_steps_tenant_status
    on public.agentic_step_executions (tenant_id, status);

create index idx_agentic_steps_runnable
    on public.agentic_step_executions (tenant_id, status, created_at);

create index idx_agentic_steps_mission_status
    on public.agentic_step_executions (tenant_id, mission_id, status);

create table public.agentic_checkpoints
(
    checkpoint_id    varchar(64)              not null
        primary key,
    tenant_id        varchar(128)             not null,
    mission_id       varchar(64)              not null
        constraint fk_agentic_checkpoints_mission
            references public.agentic_missions,
    step_id          varchar(64)
        constraint fk_agentic_checkpoints_step
            references public.agentic_step_executions,
    checkpoint_type  varchar(64)              not null,
    schema_version   integer                  not null,
    created_at       timestamp with time zone not null,
    content_type     varchar(256),
    sha256           varchar(64),
    size_bytes       bigint,
    snapshot_payload bytea,
    metadata         jsonb  default '{}'::jsonb,
    row_version      bigint default 0
);

alter table public.agentic_checkpoints
    owner to postgres;

create index idx_agentic_checkpoints_tenant_mission
    on public.agentic_checkpoints (tenant_id, mission_id, created_at);

create index idx_agentic_checkpoints_tenant_step
    on public.agentic_checkpoints (tenant_id, step_id, created_at);

create index idx_agentic_checkpoints_tenant_type
    on public.agentic_checkpoints (tenant_id, mission_id, checkpoint_type, created_at);

create table public.agentic_runtime_events
(
    id             bigint generated by default as identity
        primary key,
    tenant_id      varchar(128)              not null,
    mission_id     varchar(128)              not null
        constraint fk_agentic_runtime_events_mission
            references public.agentic_missions,
    mission_status varchar(32),
    resume_token   varchar(512)              not null,
    emitted_at     timestamp with time zone  not null,
    event_payload  jsonb default '{}'::jsonb not null,
    event_kind     varchar(32)               not null,
    constraint uq_agentic_runtime_event_resume
        unique (tenant_id, mission_id, resume_token)
);

alter table public.agentic_runtime_events
    owner to postgres;

create index idx_agentic_runtime_event_replay
    on public.agentic_runtime_events (tenant_id, mission_id, id);

create index idx_agentic_runtime_event_emitted
    on public.agentic_runtime_events (tenant_id, mission_id, emitted_at);

create table public.agentic_worker_leases
(
    lease_id     varchar(64)              not null
        primary key,
    tenant_id    varchar(128)             not null,
    mission_id   varchar(64)              not null
        constraint fk_agentic_worker_leases_mission
            references public.agentic_missions,
    step_id      varchar(64)              not null
        constraint fk_agentic_worker_leases_step
            references public.agentic_step_executions,
    worker_id    varchar(128)             not null,
    leased_at    timestamp with time zone not null,
    heartbeat_at timestamp with time zone not null,
    expires_at   timestamp with time zone not null,
    released     boolean                  not null
);

alter table public.agentic_worker_leases
    owner to postgres;

create index idx_agentic_leases_tenant_step_active
    on public.agentic_worker_leases (tenant_id, step_id, released, expires_at);

create index idx_agentic_leases_expired
    on public.agentic_worker_leases (released, expires_at);

create index idx_agentic_leases_worker
    on public.agentic_worker_leases (worker_id, released);

create table public.agentic_human_gates
(
    gate_id                 varchar(64)                not null
        primary key,
    tenant_id               varchar(128)               not null,
    mission_id              varchar(64)                not null
        constraint fk_agentic_human_gates_mission
            references public.agentic_missions,
    title                   varchar(255)               not null,
    reason                  text                       not null,
    required_reviewer_roles jsonb  default '[]'::jsonb not null,
    review_payload          jsonb  default '{}'::jsonb not null,
    status                  varchar(64)                not null,
    decided_by_user_id      varchar(128),
    decision_note           text,
    created_at              timestamp with time zone   not null,
    expires_at              timestamp with time zone,
    decided_at              timestamp with time zone,
    row_version             bigint default 0           not null
);

alter table public.agentic_human_gates
    owner to postgres;

create index idx_agentic_human_gates_tenant_mission
    on public.agentic_human_gates (tenant_id, mission_id, created_at);

create index idx_agentic_human_gates_open
    on public.agentic_human_gates (tenant_id, mission_id, status, created_at);

create index idx_agentic_human_gates_expires
    on public.agentic_human_gates (status, expires_at);

create table public.engineer_team_memberships
(
    membership_id   varchar(64)                                     not null
        primary key,
    tenant_id       varchar(128)                                    not null,
    engineer_id     varchar(64)                                     not null
        constraint fk_engineer_team_membership_engineer
            references public.engineers
            on delete cascade,
    team_id         varchar(64)                                     not null
        constraint fk_engineer_team_membership_team
            references public.teams
            on delete cascade,
    membership_role varchar(64) default 'MEMBER'::character varying not null,
    is_primary      boolean     default false                       not null,
    joined_at       timestamp with time zone                        not null,
    left_at         timestamp with time zone,
    created_at      timestamp with time zone                        not null,
    updated_at      timestamp with time zone                        not null
);

alter table public.engineer_team_memberships
    owner to postgres;

create index idx_engineer_team_memberships_engineer
    on public.engineer_team_memberships (tenant_id, engineer_id);

create index idx_engineer_team_memberships_team
    on public.engineer_team_memberships (tenant_id, team_id);

create unique index uq_engineer_team_active_membership
    on public.engineer_team_memberships (tenant_id, engineer_id, team_id)
    where (left_at IS NULL);

create unique index uq_engineer_team_primary_membership
    on public.engineer_team_memberships (tenant_id, engineer_id)
    where ((is_primary = true) AND (left_at IS NULL));

create index idx_intsvc_membership_tenant_team
    on public.engineer_team_memberships (tenant_id, team_id);

create index idx_intsvc_membership_tenant_engineer
    on public.engineer_team_memberships (tenant_id, engineer_id);

COMMIT;
