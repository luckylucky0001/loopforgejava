-- Iteration 7: team knowledge base settings and the review queue of distilled knowledge.
CREATE TABLE knowledge_settings (
    id          VARCHAR(16)   NOT NULL PRIMARY KEY,
    payload     VARCHAR       NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE knowledge_submissions (
    id           VARCHAR(64)   NOT NULL PRIMARY KEY,
    project_id   VARCHAR(200)  REFERENCES projects (id) ON DELETE CASCADE,
    status       VARCHAR(32)   NOT NULL,
    artifact_id  VARCHAR(64),
    payload      VARCHAR       NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_knowledge_submissions_artifact UNIQUE (artifact_id)
);
CREATE INDEX idx_knowledge_submissions_project ON knowledge_submissions (project_id, status, created_at);
