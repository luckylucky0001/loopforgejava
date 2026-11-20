-- Iteration 15: AI scheduled tasks and requirement auto-dispatch record what each firing did.
ALTER TABLE schedule_executions ADD COLUMN run_ids VARCHAR(4000);
ALTER TABLE schedule_executions ADD COLUMN reply VARCHAR(4000);
ALTER TABLE schedule_executions ADD COLUMN session_id VARCHAR(64);
ALTER TABLE schedule_executions ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE;
