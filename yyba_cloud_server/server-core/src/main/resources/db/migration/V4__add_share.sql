-- files 表增加分享标记：0=不分享，1=分享到家庭
ALTER TABLE files ADD COLUMN share INTEGER NOT NULL DEFAULT 0 CHECK (share IN (0, 1));

CREATE INDEX IF NOT EXISTS idx_files_share_type_time
    ON files (file_type, share, captured_at DESC) WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_files_user_share
    ON files (user_id, share) WHERE deleted_at IS NULL;
