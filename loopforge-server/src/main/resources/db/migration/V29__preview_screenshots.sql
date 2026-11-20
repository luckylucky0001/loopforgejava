-- A screenshot of the preview's entry page (upstream captureScreenshot), taken by a headless browser.
ALTER TABLE run_previews ADD COLUMN screenshot BYTEA;
ALTER TABLE run_previews ADD COLUMN screenshot_state VARCHAR(16);
ALTER TABLE run_previews ADD COLUMN screenshot_error VARCHAR(500);
