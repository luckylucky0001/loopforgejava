-- How the conversation assistant's platform writes are approved (upstream approval modes):
-- confirm (default) turns every write into a draft the speaker confirms, assisted only the risky
-- ones, auto runs them directly.
ALTER TABLE assistant_preferences ADD COLUMN approval_mode VARCHAR(16);

-- Writes the assistant proposed, waiting for the speaker's decision, and what came of them.
CREATE TABLE assistant_drafts (
    id             VARCHAR(64)   NOT NULL PRIMARY KEY,
    session_id     VARCHAR(64)   NOT NULL REFERENCES assistant_sessions (id) ON DELETE CASCADE,
    turn_id        VARCHAR(64),
    user_id        VARCHAR(64)   NOT NULL,
    tool           VARCHAR(100)  NOT NULL,
    method         VARCHAR(10)   NOT NULL,
    target         VARCHAR(2000) NOT NULL,
    body           VARCHAR,
    status         VARCHAR(16)   NOT NULL,
    result_status  INTEGER,
    result_text    VARCHAR(4000),
    decided_by     VARCHAR(200),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at     TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_assistant_drafts_session ON assistant_drafts (session_id, created_at);
