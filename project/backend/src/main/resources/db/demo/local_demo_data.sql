SET NAMES utf8mb4;
SET time_zone = '+00:00';
SET @demo_user_id = '10000000-0000-4000-8000-00000000d001';
INSERT INTO app_user (id, email, password_hash, nickname, role, status)
VALUES (@demo_user_id, 'local-demo@example.invalid', '{noop}local-only', '本地演示用户', 'USER', 'ACTIVE')
ON DUPLICATE KEY UPDATE status = 'ACTIVE', nickname = '本地演示用户';
INSERT INTO app_user (id, email, password_hash, nickname, role, status)
VALUES ('10000000-0000-4000-8000-00000000d002', 'local-other@example.invalid', '{noop}local-only', '本地越权验证用户', 'USER', 'ACTIVE')
ON DUPLICATE KEY UPDATE status = 'ACTIVE', nickname = '本地越权验证用户';
INSERT INTO user_profile (user_id, profile_version, target_role, candidate_type, cities_json, confirmed_facts_json)
VALUES (@demo_user_id, 1, 'JAVA_BACKEND', 'EXPERIENCED', JSON_ARRAY('110100'), JSON_OBJECT('education', NULL, 'graduationYear', NULL, 'experienceMonths', NULL, 'major', NULL, 'skills', JSON_ARRAY(), 'warnings', JSON_ARRAY()))
ON DUPLICATE KEY UPDATE user_id = user_id;
INSERT INTO skill_catalog (code, name, enabled) VALUES
('JAVA', 'Java', 1), ('SPRING_BOOT', 'Spring Boot', 1), ('MYSQL', 'MySQL', 1),
('REDIS', 'Redis', 1), ('SYSTEM_DESIGN', '系统设计', 1), ('COMMUNICATION', '沟通表达', 1)
ON DUPLICATE KEY UPDATE name = VALUES(name), enabled = 1;
UPDATE skill_catalog SET enabled = 0 WHERE code IN ('java', 'mysql', 'redis', 'spring');
INSERT INTO job_source (id, name, type, base_url, verification_status, verified_at)
VALUES ('20000000-0000-4000-8000-00000000d001', '本地演示来源', 'OFFICIAL', 'https://example.invalid', 'VERIFIED', UTC_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE name = VALUES(name), type = VALUES(type), base_url = VALUES(base_url), verification_status = 'VERIFIED';
INSERT INTO job (id, source_id, owner_user_id, visibility, current_version, status, source_url, verified_at)
VALUES ('30000000-0000-4000-8000-00000000d001', '20000000-0000-4000-8000-00000000d001', NULL, 'PUBLIC', 1, 'ACTIVE', 'https://example.invalid/jobs/local-demo', UTC_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE status = 'ACTIVE', verified_at = UTC_TIMESTAMP(3);
INSERT INTO job_version (id, job_id, version_no, title, company_name, city_code, role_code, candidate_type, jd_text, content_hash, requirements_json)
VALUES ('40000000-0000-4000-8000-00000000d001', '30000000-0000-4000-8000-00000000d001', 1, 'Java 后端开发工程师（本地演示）', '本地演示公司', '110100', 'JAVA_BACKEND', 'EXPERIENCED', '负责 Java 后端服务设计、开发与维护。要求熟悉 Java、Spring Boot、MySQL，具备三年以上全职后端开发经验，能够进行系统设计并编写清晰的技术文档。', UNHEX(SHA2('local-demo-job-v1', 256)), JSON_OBJECT('educationMin', NULL, 'graduationYearMin', NULL, 'graduationYearMax', NULL, 'experienceMonthsMin', 36, 'skills', JSON_ARRAY('JAVA', 'SPRING_BOOT', 'MYSQL'), 'requiredSkillLevels', JSON_OBJECT(), 'quotes', JSON_OBJECT('experienceMonths', '具备三年以上全职后端开发经验')))
ON DUPLICATE KEY UPDATE title = VALUES(title), company_name = VALUES(company_name), city_code = VALUES(city_code),
  role_code = VALUES(role_code), candidate_type = VALUES(candidate_type), jd_text = VALUES(jd_text),
  content_hash = VALUES(content_hash), requirements_json = VALUES(requirements_json);