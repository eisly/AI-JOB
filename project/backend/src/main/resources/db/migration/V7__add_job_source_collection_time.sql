ALTER TABLE job_source
    ADD COLUMN last_collected_at DATETIME(3) NULL DEFAULT NULL AFTER verified_at;