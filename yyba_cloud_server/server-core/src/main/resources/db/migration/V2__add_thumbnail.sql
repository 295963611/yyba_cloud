-- files 表增加缩略图相对路径（相对 storage.root_path）
ALTER TABLE files ADD COLUMN thumbnail_path TEXT;

CREATE INDEX IF NOT EXISTS idx_files_thumbnail ON files (thumbnail_path) WHERE thumbnail_path IS NOT NULL;
