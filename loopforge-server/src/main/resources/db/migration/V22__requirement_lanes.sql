-- Iteration 21: requirement SOP lanes. Each run of a requirement is linked to it with the SOP it ran,
-- so several lanes can run side by side; projects keep named SOP chains (playbooks).
CREATE TABLE requirement_runs (
    run_id         VARCHAR(64)  NOT NULL PRIMARY KEY REFERENCES runs (id) ON DELETE CASCADE,
    requirement_id VARCHAR(64)  NOT NULL REFERENCES requirements (id) ON DELETE CASCADE,
    workflow_id    VARCHAR(200) NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_requirement_runs_requirement ON requirement_runs (requirement_id, workflow_id);
ALTER TABLE projects ADD COLUMN sop_playbooks VARCHAR;
-- One start per lane generation: two people starting the same lane at once get one run (upstream
-- ReserveIssueDispatch). A rerun reserves the next generation.
CREATE TABLE requirement_lane_starts (
    requirement_id VARCHAR(64)  NOT NULL REFERENCES requirements (id) ON DELETE CASCADE,
    workflow_id    VARCHAR(200) NOT NULL,
    generation     INTEGER      NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (requirement_id, workflow_id, generation)
);
