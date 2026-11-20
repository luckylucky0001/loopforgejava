-- One-time markers such as the completed first-run setup (makes concurrent setups fail cleanly).
CREATE TABLE app_settings (
    setting_name   VARCHAR(100)  NOT NULL PRIMARY KEY,
    setting_value  VARCHAR(2000),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
