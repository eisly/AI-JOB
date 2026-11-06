ALTER TABLE job_source DROP CHECK ck_job_source_1;
ALTER TABLE job_source
    ADD COLUMN code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER id,
    ADD CONSTRAINT ck_job_source_1 CHECK (type IN ('OFFICIAL','AUTHORIZED','PUBLIC_SEARCH','USER_INPUT')),
    ADD UNIQUE KEY uk_job_source_code (code);

ALTER TABLE job_version
    ADD COLUMN company_size VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_type,
    ADD COLUMN published_at DATETIME(3) NULL AFTER company_size,
    ADD COLUMN fetched_at DATETIME(3) NULL AFTER published_at,
    ADD CONSTRAINT ck_job_version_3 CHECK (company_size IS NULL OR company_size IN
      ('SIZE_0_20','SIZE_21_99','SIZE_100_499','SIZE_500_999','SIZE_1000_9999','SIZE_10000_PLUS','SIZE_UNKNOWN'));

CREATE TABLE job_search (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  profile_version BIGINT UNSIGNED NOT NULL,
  resume_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  filters_json JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'GENERATING',
  query_plan_json JSON NULL,
  sources_json JSON NULL,
  summary_json JSON NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_search_task (task_id),
  UNIQUE KEY uk_job_search_batch (batch_id),
  KEY idx_job_search_user_created (user_id, created_at, id),
  CONSTRAINT ck_job_search_status CHECK (status IN ('GENERATING','READY','FAILED')),
  CONSTRAINT fk_job_search_88 FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
  CONSTRAINT fk_job_search_89 FOREIGN KEY (task_id) REFERENCES ai_task (id) ON DELETE RESTRICT,
  CONSTRAINT fk_job_search_90 FOREIGN KEY (batch_id) REFERENCES job_match_batch (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;