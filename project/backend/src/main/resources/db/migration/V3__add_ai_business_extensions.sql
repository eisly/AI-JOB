ALTER TABLE resume_version ADD COLUMN extracted_text MEDIUMTEXT NULL AFTER parse_meta_json,
    ADD COLUMN extracted_text_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER extracted_text;

CREATE TABLE resume_diagnosis (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  resume_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'GENERATING',
  items_json JSON NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_resume_diagnosis_task (task_id),
  KEY idx_resume_diagnosis_user_version_created (user_id, resume_version_id, created_at, id),
  CONSTRAINT ck_resume_diagnosis_status CHECK (status IN ('GENERATING','READY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE resume_optimization (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  resume_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  diagnosis_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'GENERATING',
  title VARCHAR(200) NULL,
  sections_json JSON NULL,
  accepted_sections_json JSON NULL,
  accepted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_resume_optimization_task (task_id),
  KEY idx_resume_optimization_user_created (user_id, created_at, id),
  CONSTRAINT ck_resume_optimization_status CHECK (status IN ('GENERATING','READY','ACCEPTED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_recommendation (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  match_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  profile_version BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'GENERATING',
  items_json JSON NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_recommendation_task (task_id),
  KEY idx_project_recommendation_user_created (user_id, created_at, id),
  CONSTRAINT ck_project_recommendation_status CHECK (status IN ('GENERATING','READY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_recommendation_item (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  recommendation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(200) NOT NULL,
  summary TEXT NOT NULL,
  skill_codes_json JSON NOT NULL,
  difficulty VARCHAR(16) NOT NULL,
  estimated_weeks INT UNSIGNED NOT NULL,
  reason TEXT NOT NULL,
  deliverables_json JSON NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_recommendation_item (recommendation_id, id),
  KEY idx_project_recommendation_item_user (user_id, recommendation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  recommendation_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(200) NULL,
  summary TEXT NULL,
  weekly_hours INT UNSIGNED NOT NULL,
  skill_codes_json JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'GENERATING',
  progress INT UNSIGNED NOT NULL DEFAULT 0,
  version INT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_task_id (task_id),
  KEY idx_project_user_created (user_id, created_at, id),
  CONSTRAINT ck_project_status CHECK (status IN ('GENERATING','ACTIVE','COMPLETED','FAILED')),
  CONSTRAINT ck_project_progress CHECK (progress BETWEEN 0 AND 100),
  CONSTRAINT ck_project_weekly_hours CHECK (weekly_hours BETWEEN 1 AND 40)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_milestone (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  seq INT UNSIGNED NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_milestone_seq (project_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_task (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  milestone_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  estimated_hours DECIMAL(6,1) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  PRIMARY KEY (id),
  KEY idx_project_task_project (project_id, milestone_id, id),
  CONSTRAINT ck_project_task_status CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED')),
  CONSTRAINT ck_project_task_hours CHECK (estimated_hours > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_deliverable (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(200) NOT NULL,
  url VARCHAR(2000) NULL,
  notes TEXT NULL,
  PRIMARY KEY (id),
  KEY idx_project_deliverable_project_created (project_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE resume_diagnosis ADD CONSTRAINT fk_resume_diagnosis_67 FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT;
ALTER TABLE resume_diagnosis ADD CONSTRAINT fk_resume_diagnosis_68 FOREIGN KEY (resume_version_id) REFERENCES resume_version (id) ON DELETE RESTRICT;
ALTER TABLE resume_diagnosis ADD CONSTRAINT fk_resume_diagnosis_69 FOREIGN KEY (task_id) REFERENCES ai_task (id) ON DELETE RESTRICT;
ALTER TABLE resume_optimization ADD CONSTRAINT fk_resume_optimization_70 FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT;
ALTER TABLE resume_optimization ADD CONSTRAINT fk_resume_optimization_71 FOREIGN KEY (resume_version_id) REFERENCES resume_version (id) ON DELETE RESTRICT;
ALTER TABLE resume_optimization ADD CONSTRAINT fk_resume_optimization_72 FOREIGN KEY (job_id) REFERENCES job (id) ON DELETE RESTRICT;
ALTER TABLE resume_optimization ADD CONSTRAINT fk_resume_optimization_73 FOREIGN KEY (diagnosis_id) REFERENCES resume_diagnosis (id) ON DELETE RESTRICT;
ALTER TABLE resume_optimization ADD CONSTRAINT fk_resume_optimization_74 FOREIGN KEY (task_id) REFERENCES ai_task (id) ON DELETE RESTRICT;
ALTER TABLE project_recommendation ADD CONSTRAINT fk_project_recommendation_75 FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT;
ALTER TABLE project_recommendation ADD CONSTRAINT fk_project_recommendation_76 FOREIGN KEY (match_item_id) REFERENCES job_match_item (id) ON DELETE RESTRICT;
ALTER TABLE project_recommendation ADD CONSTRAINT fk_project_recommendation_77 FOREIGN KEY (task_id) REFERENCES ai_task (id) ON DELETE RESTRICT;
ALTER TABLE project_recommendation_item ADD CONSTRAINT fk_project_recommendation_item_78 FOREIGN KEY (recommendation_id) REFERENCES project_recommendation (id) ON DELETE RESTRICT;
ALTER TABLE project_recommendation_item ADD CONSTRAINT fk_project_recommendation_item_79 FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT;
ALTER TABLE project ADD CONSTRAINT fk_project_80 FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT;
ALTER TABLE project ADD CONSTRAINT fk_project_81 FOREIGN KEY (recommendation_item_id) REFERENCES project_recommendation_item (id) ON DELETE RESTRICT;
ALTER TABLE project ADD CONSTRAINT fk_project_82 FOREIGN KEY (task_id) REFERENCES ai_task (id) ON DELETE RESTRICT;
ALTER TABLE project_milestone ADD CONSTRAINT fk_project_milestone_83 FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT;
ALTER TABLE project_task ADD CONSTRAINT fk_project_task_84 FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT;
ALTER TABLE project_task ADD CONSTRAINT fk_project_task_85 FOREIGN KEY (milestone_id) REFERENCES project_milestone (id) ON DELETE RESTRICT;
ALTER TABLE project_deliverable ADD CONSTRAINT fk_project_deliverable_86 FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT;
ALTER TABLE project_deliverable ADD CONSTRAINT fk_project_deliverable_87 FOREIGN KEY (task_id) REFERENCES project_task (id) ON DELETE RESTRICT;