-- AI-JOB demo data; execute manually after V1 on an empty development database.
-- The records are fictional. The demo accounts are disabled and cannot be used to log in.
-- Do not execute in production. This script is not a Flyway migration.
SET NAMES utf8mb4;
SET time_zone = '+00:00';
START TRANSACTION;

INSERT INTO app_user (id, email, password_hash, nickname, role, status) VALUES
('11111111-1111-4111-8111-111111111111', 'candidate@example.invalid', '{disabled}demo', '演示求职者', 'USER', 'DISABLED'),
('22222222-2222-4222-8222-222222222222', 'admin@example.invalid', '{disabled}demo', '演示管理员', 'ADMIN', 'DISABLED');

INSERT INTO skill_catalog (code, name, parent_code, enabled) VALUES
('java', 'Java', NULL, 1),
('mysql', 'MySQL', NULL, 1),
('redis', 'Redis', NULL, 1),
('spring', 'Spring', 'java', 1);

INSERT INTO user_profile (user_id, profile_version, target_role, candidate_type, cities_json,
                          min_monthly_salary_cents, max_monthly_salary_cents, confirmed_facts_json)
VALUES ('11111111-1111-4111-8111-111111111111', 0, 'JAVA_BACKEND', 'CAMPUS', JSON_ARRAY('330100'),
        1000000, 1500000, JSON_OBJECT('education', NULL, 'graduationYear', NULL, 'major', NULL, 'skills', JSON_ARRAY()));

-- A personal JD is intentionally unverified and visible only to its owner.
INSERT INTO job_source (id, name, type, verification_status, verification_note) VALUES
('33333333-3333-4333-8333-333333333333', '个人输入（演示）', 'USER_INPUT', 'UNVERIFIED', '虚构演示数据，不代表真实招聘来源');

INSERT INTO job (id, source_id, owner_user_id, visibility, current_version, status) VALUES
('44444444-4444-4444-8444-444444444444', '33333333-3333-4333-8333-333333333333',
 '11111111-1111-4111-8111-111111111111', 'PRIVATE', 1, 'DRAFT');

INSERT INTO job_version (id, job_id, version_no, title, company_name, city_code, role_code,
                         candidate_type, min_monthly_salary_cents, max_monthly_salary_cents,
                         jd_text, content_hash)
VALUES ('55555555-5555-4555-8555-555555555555', '44444444-4444-4444-8444-444444444444', 1,
        'Java 后端开发（演示）', '虚构科技公司', '330100', 'JAVA_BACKEND', 'CAMPUS', 1000000, 1500000,
        '虚构岗位：了解 Java、MySQL 和 Redis，具备基础后端开发能力。',
        UNHEX(SHA2('虚构岗位：了解 Java、MySQL 和 Redis，具备基础后端开发能力。', 256)));

INSERT INTO job_favorite (id, user_id, job_id) VALUES
('66666666-6666-4666-8666-666666666666', '11111111-1111-4111-8111-111111111111',
 '44444444-4444-4444-8444-444444444444');

INSERT INTO knowledge_document (id, title, content, skill_codes_json, license_note, status, index_status, created_by) VALUES
('77777777-7777-4777-8777-777777777777', 'MySQL 索引基础（演示）',
 '索引能帮助数据库减少需要扫描的数据，但也会增加写入和存储成本。', JSON_ARRAY('mysql'),
 '项目演示用原创示例', 'ACTIVE', 'PENDING', '22222222-2222-4222-8222-222222222222');

INSERT INTO question (id, root_question_id, question_version, stem, type, skill_codes_json,
                      difficulty, source_type, reference_answer, rubric_json, citations_json, status, created_by) VALUES
('88888888-8888-4888-8888-888888888888', '88888888-8888-4888-8888-888888888888', 1,
 '请解释数据库索引的作用，并说明索引的代价。', 'TEXT_KNOWLEDGE', JSON_ARRAY('mysql'),
 'EASY', 'CURATED', '索引可减少查询扫描范围；代价包括额外存储和写入维护开销。',
 JSON_OBJECT('criteria', JSON_ARRAY(JSON_OBJECT('name', '查询作用', 'weight', 0.5),
                                    JSON_OBJECT('name', '维护代价', 'weight', 0.5))),
 JSON_ARRAY(), 'ACTIVE', '22222222-2222-4222-8222-222222222222');

COMMIT;
