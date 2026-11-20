-- Security fix: a conversation token is valid for the one turn it was issued for, not the session.
ALTER TABLE assistant_sessions ADD COLUMN turn_id VARCHAR(64);
