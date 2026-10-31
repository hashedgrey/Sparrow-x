-- Fail container initialization if the expected schema was not created.
DO $sparrowx$
DECLARE
    actual_count integer;
BEGIN
    SELECT count(*) INTO actual_count
    FROM pg_catalog.pg_tables
    WHERE schemaname = 'public'
      AND tablename IN (
        'inbox_messages',
        'outbox_messages',
        'documents',
        'ingestion_jobs',
        'document_chunks',
        'engineers',
        'teams',
        'modules',
        'repositories',
        'internal_documents',
        'runbooks',
        'agentic_missions',
        'agentic_mission_executions',
        'agentic_step_executions',
        'agentic_checkpoints',
        'agentic_runtime_events',
        'agentic_worker_leases',
        'agentic_human_gates',
        'engineer_team_memberships'
      );
    IF actual_count <> 19 THEN
        RAISE EXCEPTION 'SparrowX schema incomplete: expected 19 tables, found %', actual_count;
    END IF;
END
$sparrowx$;
