-- Live engine instances (iteration 4): run leases are taken over when an owner stops heartbeating.
CREATE TABLE engine_instances (
    id            VARCHAR(200)  NOT NULL PRIMARY KEY,
    host          VARCHAR(255)  NOT NULL,
    pid           BIGINT        NOT NULL,
    started_at    TIMESTAMP WITH TIME ZONE,
    heartbeat_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_engine_instances_host ON engine_instances (host);
