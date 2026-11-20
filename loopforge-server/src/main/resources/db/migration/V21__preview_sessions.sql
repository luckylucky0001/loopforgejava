-- Iteration 20: preview lifecycle (ready / skipped / failed / expired), backend address and
-- revocable, sliding preview sessions (upstream preview sessions; only token hashes are stored).
ALTER TABLE run_previews ADD COLUMN status VARCHAR(16) DEFAULT 'ready' NOT NULL;
ALTER TABLE run_previews ADD COLUMN error_text VARCHAR(1000);
ALTER TABLE run_previews ADD COLUMN backend_url VARCHAR(1024);
ALTER TABLE run_previews ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE run_previews ADD COLUMN last_accessed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE run_previews ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE run_previews ALTER COLUMN node_id DROP NOT NULL;
CREATE INDEX idx_run_previews_status ON run_previews (status, expires_at);

CREATE TABLE preview_sessions (
    id               VARCHAR(64)   NOT NULL PRIMARY KEY,
    run_id           VARCHAR(64)   NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    token_hash       VARCHAR(64)   NOT NULL UNIQUE,
    backend_url      VARCHAR(1024),
    created_by       VARCHAR(128),
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    last_accessed_at TIMESTAMP WITH TIME ZONE,
    revoked_at       TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_preview_sessions_run ON preview_sessions (run_id);
