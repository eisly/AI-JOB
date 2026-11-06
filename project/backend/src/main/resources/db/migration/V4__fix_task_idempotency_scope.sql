ALTER TABLE ai_task DROP INDEX uk_idempotency_key,
    ADD INDEX idx_ai_task_idempotency_key (idempotency_key);