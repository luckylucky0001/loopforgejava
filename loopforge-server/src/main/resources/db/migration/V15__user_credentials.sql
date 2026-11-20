-- Iteration 14: personal credentials (model keys and base URLs per executor, Git tokens per host).
-- Secrets are AES-GCM ciphertext bound to their row; the public part holds non-secret settings.
CREATE TABLE user_credentials (
    user_id      VARCHAR(64)   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind         VARCHAR(160)  NOT NULL,
    secret       VARCHAR       NOT NULL,
    public_info  VARCHAR       NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_credentials PRIMARY KEY (user_id, kind)
);
