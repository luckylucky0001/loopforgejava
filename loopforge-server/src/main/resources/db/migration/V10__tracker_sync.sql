-- Iteration 9: requirements synced from GitHub / GitLab / TAPD, and per-project tracker settings.
ALTER TABLE requirements ADD COLUMN external_key VARCHAR(400);
CREATE UNIQUE INDEX uq_requirements_external ON requirements (project_id, external_key);

CREATE TABLE project_trackers (
    project_id  VARCHAR(200)  NOT NULL PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,
    payload     VARCHAR       NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
