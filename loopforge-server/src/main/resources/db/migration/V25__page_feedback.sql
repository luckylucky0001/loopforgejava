-- Reports from the console's floating feedback widget (upstream page_feedbacks). They belong to no
-- project; the submitter is copied from the session, never from the request.
CREATE TABLE page_feedbacks (
    id            VARCHAR(64)   NOT NULL PRIMARY KEY,
    title         VARCHAR(500)  NOT NULL,
    description   VARCHAR(20000),
    url           VARCHAR(2048),
    route         VARCHAR(512),
    page_view     VARCHAR(128),
    frame_url     VARCHAR(2048),
    context       VARCHAR(4000) NOT NULL,
    element       VARCHAR(4000),
    user_id       VARCHAR(64),
    username      VARCHAR(200),
    display_name  VARCHAR(200),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_page_feedbacks_created ON page_feedbacks (created_at);
CREATE INDEX idx_page_feedbacks_user ON page_feedbacks (user_id);

CREATE TABLE page_feedback_images (
    id           VARCHAR(64)   NOT NULL PRIMARY KEY,
    feedback_id  VARCHAR(64)   NOT NULL REFERENCES page_feedbacks (id) ON DELETE CASCADE,
    seq          INTEGER       NOT NULL,
    file_name    VARCHAR(255),
    mime_type    VARCHAR(64)   NOT NULL,
    byte_size    BIGINT        NOT NULL,
    sha256       VARCHAR(64)   NOT NULL,
    data         BYTEA         NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_page_feedback_images_feedback ON page_feedback_images (feedback_id);
