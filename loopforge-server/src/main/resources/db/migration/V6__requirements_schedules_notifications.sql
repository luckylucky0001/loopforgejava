-- Iteration 5: requirements, scheduled runs and notification channels (JSON payload + indexed columns).
CREATE TABLE requirements (
    id             VARCHAR(64)   NOT NULL PRIMARY KEY,
    project_id     VARCHAR(200)  NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    number         INTEGER       NOT NULL,
    status         VARCHAR(32)   NOT NULL,
    latest_run_id  VARCHAR(64),
    payload        VARCHAR       NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_requirements_number UNIQUE (project_id, number)
);
CREATE INDEX idx_requirements_run ON requirements (latest_run_id);

CREATE TABLE schedules (
    id           VARCHAR(64)   NOT NULL PRIMARY KEY,
    project_id   VARCHAR(200)  NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    enabled      BOOLEAN       NOT NULL,
    next_run_at  TIMESTAMP WITH TIME ZONE,
    payload      VARCHAR       NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_schedules_due ON schedules (enabled, next_run_at);

CREATE TABLE notification_channels (
    id          VARCHAR(64)   NOT NULL PRIMARY KEY,
    project_id  VARCHAR(200)  REFERENCES projects (id) ON DELETE CASCADE,
    enabled     BOOLEAN       NOT NULL,
    payload     VARCHAR       NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_notification_channels_project ON notification_channels (project_id);
