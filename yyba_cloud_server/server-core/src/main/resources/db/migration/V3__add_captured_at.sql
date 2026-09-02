-- files 表增加拍摄/采集时间（ISO-8601），用于相册按时间分页
ALTER TABLE files ADD COLUMN captured_at TEXT;

CREATE INDEX IF NOT EXISTS idx_files_user_captured
    ON files (user_id, captured_at DESC) WHERE deleted_at IS NULL;

UPDATE files SET captured_at = created_at WHERE captured_at IS NULL;

-- 上传会话暂存客户端拍摄时间，合并时写入 files
ALTER TABLE upload_sessions ADD COLUMN captured_at TEXT;
