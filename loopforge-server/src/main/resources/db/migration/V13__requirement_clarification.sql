-- Iteration 12: AI clarification of requirements. The status is a column so rounds can be claimed
-- atomically; the rest (history, questions, conclusion, write-back) is the JSON payload.
CREATE TABLE requirement_clarifications (
    requirement_id    VARCHAR(64)   NOT NULL PRIMARY KEY REFERENCES requirements (id) ON DELETE CASCADE,
    status            VARCHAR(16)   NOT NULL,
    round_started_at  TIMESTAMP WITH TIME ZONE,
    payload           VARCHAR       NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);
