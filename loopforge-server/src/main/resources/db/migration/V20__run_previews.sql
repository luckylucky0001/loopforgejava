-- Iteration 19: static sites archived by preview nodes (the latest per run), served through signed links.
CREATE TABLE run_previews (
    run_id      VARCHAR(64)   NOT NULL PRIMARY KEY REFERENCES runs (id) ON DELETE CASCADE,
    node_id     VARCHAR(128)  NOT NULL,
    site_path   VARCHAR(1024) NOT NULL,
    index_file  VARCHAR(1024) NOT NULL,
    spa         BOOLEAN       NOT NULL,
    file_count  INTEGER       NOT NULL,
    total_bytes BIGINT        NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE run_preview_files (
    run_id  VARCHAR(64)   NOT NULL REFERENCES run_previews (run_id) ON DELETE CASCADE,
    path    VARCHAR(1024) NOT NULL,
    content BYTEA         NOT NULL,
    PRIMARY KEY (run_id, path)
);
