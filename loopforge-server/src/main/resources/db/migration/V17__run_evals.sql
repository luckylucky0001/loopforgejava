-- Iteration 16: AI quality evaluations of settled runs (the newest 10 per run are kept).
CREATE TABLE run_evals (
    id          VARCHAR(64)   NOT NULL PRIMARY KEY,
    run_id      VARCHAR(64)   NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    status      VARCHAR(16)   NOT NULL,
    payload     VARCHAR       NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_run_evals_run ON run_evals (run_id, created_at);
