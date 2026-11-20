-- Iteration 5: accounts, tokens, projects and members; run visibility columns.
CREATE TABLE users (
    id             VARCHAR(64)   NOT NULL PRIMARY KEY,
    username       VARCHAR(100)  NOT NULL UNIQUE,
    display_name   VARCHAR(200),
    email          VARCHAR(320),
    password_hash  VARCHAR(200)  NOT NULL,
    role           VARCHAR(16)   NOT NULL,
    disabled       BOOLEAN       NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE user_tokens (
    id            VARCHAR(64)   NOT NULL PRIMARY KEY,
    user_id       VARCHAR(64)   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          VARCHAR(200)  NOT NULL,
    kind          VARCHAR(16)   NOT NULL,
    token_hash    CHAR(64)      NOT NULL UNIQUE,
    prefix        VARCHAR(16)   NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at  TIMESTAMP WITH TIME ZONE,
    expires_at    TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_user_tokens_user ON user_tokens (user_id);

CREATE TABLE projects (
    id           VARCHAR(200)  NOT NULL PRIMARY KEY,
    name         VARCHAR(500)  NOT NULL,
    description  VARCHAR,
    created_by   VARCHAR(100),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE project_members (
    project_id  VARCHAR(200)  NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id     VARCHAR(64)   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role        VARCHAR(16)   NOT NULL,
    PRIMARY KEY (project_id, user_id)
);
CREATE INDEX idx_project_members_user ON project_members (user_id);

ALTER TABLE runs ADD COLUMN project_id VARCHAR(200);
ALTER TABLE runs ADD COLUMN triggered_by VARCHAR(200);
CREATE INDEX idx_runs_project ON runs (project_id, created_at);
CREATE INDEX idx_runs_triggered_by ON runs (triggered_by, created_at);
