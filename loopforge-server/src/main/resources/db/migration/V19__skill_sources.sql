-- Skills installed from the SkillHub market: where each came from, for updates.
CREATE TABLE skill_sources (
    skill_id      VARCHAR(200)  NOT NULL PRIMARY KEY,
    namespace     VARCHAR(200),
    slug          VARCHAR(200)  NOT NULL,
    version       VARCHAR(100)  NOT NULL,
    installed_by  VARCHAR(200),
    installed_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_skill_sources_slug ON skill_sources (namespace, slug);
