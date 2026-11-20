-- Messages sent while the assistant is still answering (upstream queue / steer on send): they run
-- one after another once the current reply ends; a steering message runs first.
CREATE TABLE assistant_queue (
    id          VARCHAR(64)   NOT NULL PRIMARY KEY,
    session_id  VARCHAR(64)   NOT NULL REFERENCES assistant_sessions (id) ON DELETE CASCADE,
    speaker_id  VARCHAR(64)   NOT NULL,
    speaker     VARCHAR(200)  NOT NULL,
    content     VARCHAR       NOT NULL,
    priority    INTEGER       NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_assistant_queue_session ON assistant_queue (session_id, priority, created_at);

-- Whether sending during a reply interrupts it (upstream assistant_preferences.steer_on_send).
CREATE TABLE assistant_preferences (
    user_id        VARCHAR(64)  NOT NULL PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    steer_on_send  BOOLEAN      NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

-- The last message each person has seen per conversation (upstream session reads).
CREATE TABLE assistant_reads (
    session_id  VARCHAR(64)  NOT NULL REFERENCES assistant_sessions (id) ON DELETE CASCADE,
    user_id     VARCHAR(64)  NOT NULL,
    last_seq    BIGINT       NOT NULL,
    PRIMARY KEY (session_id, user_id)
);
