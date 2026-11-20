-- One snapshot per run workspace (upstream run_workspace_checkpoints), saved before a finished run's
-- workspace is released and restored when the run is replayed; it goes with the run.
CREATE TABLE run_workspace_snapshots (
    run_id      VARCHAR(64)   NOT NULL PRIMARY KEY REFERENCES runs (id) ON DELETE CASCADE,
    archive     BYTEA         NOT NULL,
    size_bytes  BIGINT        NOT NULL,
    files       INTEGER       NOT NULL,
    content     BIGINT        NOT NULL,
    sha256      VARCHAR(64)   NOT NULL,
    saved_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
