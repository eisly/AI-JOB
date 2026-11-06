SET NAMES utf8mb4;
SET time_zone = '+00:00';

INSERT INTO skill_catalog (code, name, enabled)
VALUES ('JAVA', 'Java', 1), ('SPRING_BOOT', 'Spring Boot', 1), ('MYSQL', 'MySQL', 1),
       ('REDIS', 'Redis', 1), ('SYSTEM_DESIGN', '系统设计', 1)
ON DUPLICATE KEY UPDATE name = VALUES(name), enabled = 1;

INSERT INTO app_user (id, email, password_hash, nickname, role, status)
VALUES
  ('a0000000-0000-4000-8000-000000000001', 'e2e-user1@example.invalid', '{noop}local-only', 'E2E 用户一', 'USER', 'ACTIVE'),
  ('a0000000-0000-4000-8000-000000000002', 'e2e-user2@example.invalid', '{noop}local-only', 'E2E 用户二', 'USER', 'ACTIVE')
ON DUPLICATE KEY UPDATE status = 'ACTIVE', nickname = VALUES(nickname);

INSERT INTO resume (id, user_id, title, latest_version_id)
VALUES
  ('b0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001', 'E2E 虚构简历一', NULL),
  ('b0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000002', 'E2E 虚构简历二', NULL)
ON DUPLICATE KEY UPDATE deleted_at = NULL;

INSERT INTO resume_version
  (id, resume_id, user_id, version_no, file_name, storage_key, mime_type, size_bytes, sha256, status,
   parsed_facts_json, confirmed_facts_json, confirmed_at)
VALUES
  ('c0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000001',
   'a0000000-0000-4000-8000-000000000001', 1, 'e2e-resume-1.txt', 'e2e/seed/1', 'text/plain', 128,
   UNHEX(SHA2('e2e-resume-1', 256)), 'CONFIRMED',
   JSON_OBJECT('education', 'BACHELOR', 'graduationYear', 2022, 'experienceMonths', 36, 'major', '计算机科学与技术',
     'skills', JSON_ARRAY(
       JSON_OBJECT('skillCode', 'JAVA', 'claim', '项目使用 Java', 'quote', '虚构简历：Java 项目开发', 'location', '项目', 'confirmed', TRUE),
       JSON_OBJECT('skillCode', 'SPRING_BOOT', 'claim', '项目使用 Spring Boot', 'quote', '虚构简历：Spring Boot 服务', 'location', '项目', 'confirmed', TRUE)
     ), 'warnings', JSON_ARRAY()),
   JSON_OBJECT('education', 'BACHELOR', 'graduationYear', 2022, 'experienceMonths', 36, 'major', '计算机科学与技术',
     'skills', JSON_ARRAY(
       JSON_OBJECT('skillCode', 'JAVA', 'claim', '项目使用 Java', 'quote', '虚构简历：Java 项目开发', 'location', '项目', 'confirmed', TRUE),
       JSON_OBJECT('skillCode', 'SPRING_BOOT', 'claim', '项目使用 Spring Boot', 'quote', '虚构简历：Spring Boot 服务', 'location', '项目', 'confirmed', TRUE)
     ), 'warnings', JSON_ARRAY()),
   UTC_TIMESTAMP(3)),
  ('c0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000002',
   'a0000000-0000-4000-8000-000000000002', 1, 'e2e-resume-2.txt', 'e2e/seed/2', 'text/plain', 128,
   UNHEX(SHA2('e2e-resume-2', 256)), 'CONFIRMED',
   JSON_OBJECT('education', 'BACHELOR', 'graduationYear', 2022, 'experienceMonths', 36, 'major', '计算机科学与技术',
     'skills', JSON_ARRAY(
       JSON_OBJECT('skillCode', 'JAVA', 'claim', '项目使用 Java', 'quote', '虚构简历：Java 项目开发', 'location', '项目', 'confirmed', TRUE)
     ), 'warnings', JSON_ARRAY()),
   JSON_OBJECT('education', 'BACHELOR', 'graduationYear', 2022, 'experienceMonths', 36, 'major', '计算机科学与技术',
     'skills', JSON_ARRAY(
       JSON_OBJECT('skillCode', 'JAVA', 'claim', '项目使用 Java', 'quote', '虚构简历：Java 项目开发', 'location', '项目', 'confirmed', TRUE)
     ), 'warnings', JSON_ARRAY()),
   UTC_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE status = 'CONFIRMED', confirmed_facts_json = VALUES(confirmed_facts_json),
    parsed_facts_json = VALUES(parsed_facts_json), confirmed_at = UTC_TIMESTAMP(3), deleted_at = NULL;

UPDATE resume SET latest_version_id = CASE id
    WHEN 'b0000000-0000-4000-8000-000000000001' THEN 'c0000000-0000-4000-8000-000000000001'
    WHEN 'b0000000-0000-4000-8000-000000000002' THEN 'c0000000-0000-4000-8000-000000000002'
  END
WHERE id IN ('b0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000002');

INSERT INTO user_profile
  (user_id, profile_version, target_role, candidate_type, cities_json, min_monthly_salary_cents,
   max_monthly_salary_cents, confirmed_facts_json, active_resume_version_id)
VALUES
  ('a0000000-0000-4000-8000-000000000001', 3, 'JAVA_BACKEND', 'EXPERIENCED', JSON_ARRAY('440300'), NULL, NULL,
   JSON_OBJECT('education', 'BACHELOR', 'graduationYear', 2022, 'experienceMonths', 36, 'major', '计算机科学与技术',
     'skills', JSON_ARRAY(
       JSON_OBJECT('skillCode', 'JAVA', 'claim', '项目使用 Java', 'quote', '虚构简历：Java 项目开发', 'location', '项目', 'confirmed', TRUE),
       JSON_OBJECT('skillCode', 'SPRING_BOOT', 'claim', '项目使用 Spring Boot', 'quote', '虚构简历：Spring Boot 服务', 'location', '项目', 'confirmed', TRUE)
     ), 'warnings', JSON_ARRAY()),
   'c0000000-0000-4000-8000-000000000001'),
  ('a0000000-0000-4000-8000-000000000002', 3, 'JAVA_BACKEND', 'EXPERIENCED', JSON_ARRAY('440300'), NULL, NULL,
   JSON_OBJECT('education', 'BACHELOR', 'graduationYear', 2022, 'experienceMonths', 36, 'major', '计算机科学与技术',
     'skills', JSON_ARRAY(
       JSON_OBJECT('skillCode', 'JAVA', 'claim', '项目使用 Java', 'quote', '虚构简历：Java 项目开发', 'location', '项目', 'confirmed', TRUE)
     ), 'warnings', JSON_ARRAY()),
   'c0000000-0000-4000-8000-000000000002')
ON DUPLICATE KEY UPDATE profile_version = 3, target_role = 'JAVA_BACKEND', candidate_type = 'EXPERIENCED',
    cities_json = JSON_ARRAY('440300'), confirmed_facts_json = VALUES(confirmed_facts_json),
    active_resume_version_id = VALUES(active_resume_version_id);

INSERT INTO job_source (id, code, name, type, base_url, verification_status, verified_at)
VALUES ('20000000-0000-4000-8000-000000000001', 'local-e2e', '本地 E2E 来源', 'OFFICIAL',
        'https://example.invalid', 'VERIFIED', UTC_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE name = VALUES(name), type = 'OFFICIAL', verification_status = 'VERIFIED',
    verified_at = UTC_TIMESTAMP(3);

INSERT INTO job (id, source_id, owner_user_id, visibility, current_version, status, source_url, verified_at)
VALUES
  ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', NULL, 'PUBLIC', 1,
   'ACTIVE', 'https://example.invalid/jobs/e2e-pass', UTC_TIMESTAMP(3)),
  ('30000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000001', NULL, 'PUBLIC', 1,
   'ACTIVE', 'https://example.invalid/jobs/e2e-fail', UTC_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE status = 'ACTIVE', verified_at = UTC_TIMESTAMP(3);

INSERT INTO job_version
  (id, job_id, version_no, title, company_name, city_code, role_code, candidate_type, company_size,
   published_at, fetched_at, jd_text, content_hash, requirements_json)
VALUES
  ('40000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', 1,
   'Java 后端开发工程师（E2E）', 'E2E 科技有限公司', '440300', 'JAVA_BACKEND', 'EXPERIENCED', 'SIZE_100_499',
   UTC_TIMESTAMP(3), UTC_TIMESTAMP(3),
   '负责 Java 后端服务设计与开发。具备三年以上后端开发经验，熟悉 Java、Spring Boot、MySQL，能够完成系统设计。',
   UNHEX(SHA2('e2e-pass-v1', 256)),
   JSON_OBJECT('educationMin', NULL, 'graduationYearMin', NULL, 'graduationYearMax', NULL,
     'experienceMonthsMin', 36, 'skills', JSON_ARRAY('JAVA', 'SPRING_BOOT', 'MYSQL'),
     'requiredSkillLevels', JSON_OBJECT(), 'quotes', JSON_OBJECT('experienceMonthsMin', '具备三年以上后端开发经验'))),
  ('40000000-0000-4000-8000-000000000002', '30000000-0000-4000-8000-000000000002', 1,
   'Java 高级工程师（E2E 资格失败）', 'E2E 高门槛公司', '440300', 'JAVA_BACKEND', 'EXPERIENCED', 'SIZE_1000_9999',
   UTC_TIMESTAMP(3), UTC_TIMESTAMP(3),
   '负责 Java 平台研发。硕士及以上学历，五年以上工作经验，熟悉 Java、Spring Boot。',
   UNHEX(SHA2('e2e-fail-v1', 256)),
   JSON_OBJECT('educationMin', 'MASTER', 'graduationYearMin', NULL, 'graduationYearMax', NULL,
     'experienceMonthsMin', 60, 'skills', JSON_ARRAY('JAVA', 'SPRING_BOOT'),
     'requiredSkillLevels', JSON_OBJECT(), 'quotes',
     JSON_OBJECT('educationMin', '硕士及以上学历', 'experienceMonthsMin', '五年以上工作经验')))
ON DUPLICATE KEY UPDATE title = VALUES(title), company_name = VALUES(company_name), city_code = VALUES(city_code),
    role_code = VALUES(role_code), candidate_type = VALUES(candidate_type), company_size = VALUES(company_size),
    published_at = VALUES(published_at), fetched_at = VALUES(fetched_at), jd_text = VALUES(jd_text),
    content_hash = VALUES(content_hash), requirements_json = VALUES(requirements_json);