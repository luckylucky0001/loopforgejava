-- Rules, skills and MCP servers (iteration 3), one row per asset, payload as JSON.
CREATE TABLE catalog_assets (
    kind        VARCHAR(32)   NOT NULL,
    id          VARCHAR(200)  NOT NULL,
    name        VARCHAR(500)  NOT NULL,
    payload     VARCHAR       NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (kind, id)
);
