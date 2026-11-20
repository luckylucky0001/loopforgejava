-- Repository catalog (iteration 2). Runs freeze a copy of the repository in their payload.
CREATE TABLE repositories (
    id          VARCHAR(200)  NOT NULL PRIMARY KEY,
    name        VARCHAR(500)  NOT NULL,
    payload     VARCHAR       NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
