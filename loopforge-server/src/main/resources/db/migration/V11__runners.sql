-- Iteration 10: execution machines (personal and shared pool runners).
CREATE TABLE runners (
    id            VARCHAR(64)   NOT NULL PRIMARY KEY,
    name          VARCHAR(200)  NOT NULL,
    owner_id      VARCHAR(64)   REFERENCES users (id) ON DELETE CASCADE,
    shared        BOOLEAN       NOT NULL,
    token_hash    CHAR(64)      NOT NULL UNIQUE,
    token_prefix  VARCHAR(16)   NOT NULL,
    payload       VARCHAR       NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at  TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_runners_owner ON runners (owner_id);

ALTER TABLE runs ADD COLUMN runner_id VARCHAR(64);
CREATE INDEX idx_runs_runner ON runs (runner_id, status);
