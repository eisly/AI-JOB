ALTER TABLE project_recommendation
  ADD COLUMN root_recommendation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN previous_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN discovery_round TINYINT UNSIGNED NULL,
  ADD COLUMN search_state_json JSON NULL,
  ADD UNIQUE KEY ux_project_recommendation_previous_batch (previous_batch_id),
  ADD KEY idx_project_recommendation_discovery (root_recommendation_id, discovery_round);
