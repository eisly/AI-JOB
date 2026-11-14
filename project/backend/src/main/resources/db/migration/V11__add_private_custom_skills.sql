ALTER TABLE skill_catalog
  ADD COLUMN owner_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN normalization_status VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
  ADD UNIQUE KEY uk_skill_owner_name (owner_user_id, name),
  ADD CONSTRAINT fk_skill_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
  ADD CONSTRAINT ck_skill_normalization CHECK (normalization_status IN ('STANDARD','PENDING'));

INSERT IGNORE INTO skill_catalog (code, name, enabled) VALUES
  ('PYTHON', 'Python', 1), ('C', 'C', 1), ('CPP', 'C++', 1),
  ('C_SHARP', 'C#', 1), ('JAVASCRIPT', 'JavaScript', 1),
  ('TYPESCRIPT', 'TypeScript', 1), ('GO', 'Go', 1), ('RUST', 'Rust', 1),
  ('FASTAPI', 'FastAPI', 1), ('VUE', 'Vue', 1),
  ('DOCKER', 'Docker', 1), ('KUBERNETES', 'Kubernetes', 1),
  ('NETWORK_SECURITY', '网络安全', 1), ('ISO_21434', 'ISO 21434', 1);
