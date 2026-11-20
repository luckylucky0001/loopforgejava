-- Iteration 23: business domains group projects for delivery reports; assignments keep a history.
CREATE TABLE business_domains (
    id          VARCHAR(64)   NOT NULL PRIMARY KEY,
    name        VARCHAR(200)  NOT NULL UNIQUE,
    description VARCHAR(4000),
    active      BOOLEAN       NOT NULL,
    managers    VARCHAR       NOT NULL,
    viewers     VARCHAR       NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
ALTER TABLE projects ADD COLUMN business_domain_id VARCHAR(64) REFERENCES business_domains (id);
CREATE TABLE business_domain_assignments (
    id             VARCHAR(64)   NOT NULL PRIMARY KEY,
    project_id     VARCHAR(200)  NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    from_domain_id VARCHAR(64),
    to_domain_id   VARCHAR(64),
    actor          VARCHAR(100),
    reason         VARCHAR(1000),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_business_domain_assignments_project ON business_domain_assignments (project_id, created_at);
