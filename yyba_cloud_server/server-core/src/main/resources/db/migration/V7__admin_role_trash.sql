-- 用户角色：ADMIN / MEMBER
ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'MEMBER';
UPDATE users SET role = 'ADMIN' WHERE id = (SELECT id FROM users ORDER BY created_at ASC LIMIT 1);

-- 回收站保留天数
INSERT OR IGNORE INTO app_config (config_key, config_value, description, updated_at)
VALUES ('trash.retention_days', '30', '回收站保留天数', datetime('now'));
