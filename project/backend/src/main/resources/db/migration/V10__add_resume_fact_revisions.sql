CREATE TABLE resume_fact_revision (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  resume_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision_no INT UNSIGNED NOT NULL,
  profile_version INT UNSIGNED NULL,
  facts_json JSON NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_resume_fact_revision_version_no (resume_version_id, revision_no),
  CONSTRAINT fk_resume_fact_revision_version FOREIGN KEY (resume_version_id) REFERENCES resume_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
