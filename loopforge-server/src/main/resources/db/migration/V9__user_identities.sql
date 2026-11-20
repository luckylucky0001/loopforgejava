-- Iteration 8: accounts signed in through an OpenID Connect provider, keyed by issuer and subject.
CREATE TABLE user_identities (
    issuer      VARCHAR(500)  NOT NULL,
    subject     VARCHAR(255)  NOT NULL,
    user_id     VARCHAR(64)   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (issuer, subject)
);
CREATE INDEX idx_user_identities_user ON user_identities (user_id);
