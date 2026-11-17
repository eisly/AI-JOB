-- V14: 保存项目推荐的来源和 GitHub 仓库元数据，区分真实仓库与 AI 练习项目。
ALTER TABLE project_recommendation_item
  ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'AI_GENERATED' AFTER deliverables_json,
  ADD COLUMN recommendation_source VARCHAR(32) NOT NULL DEFAULT 'MODEL' AFTER source_type,
  ADD COLUMN repository_json JSON NULL AFTER recommendation_source;

ALTER TABLE project_recommendation_item
  ADD CONSTRAINT ck_project_recommendation_item_source_type
    CHECK (source_type IN ('GITHUB_REPOSITORY', 'AI_GENERATED')),
  ADD CONSTRAINT ck_project_recommendation_item_source
    CHECK (recommendation_source IN ('GITHUB_API', 'MODEL', 'LOCAL_RULES'));
