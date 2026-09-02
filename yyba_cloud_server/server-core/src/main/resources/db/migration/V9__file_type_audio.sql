/* flyway:executeInTransaction=false */
-- 扩展 file_type 允许 AUDIO（磁盘索引与上传均会写入 AUDIO）
-- SQLite 无法直接修改 CHECK，需重建 files 表

PRAGMA foreign_keys = OFF;

CREATE TABLE files_new (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id        INTEGER NOT NULL REFERENCES users(id),
    parent_path    TEXT    NOT NULL DEFAULT '',
    file_name      TEXT    NOT NULL,
    relative_path  TEXT    NOT NULL,
    file_size      INTEGER NOT NULL CHECK (file_size >= 0),
    mime_type      TEXT,
    sha256         TEXT,
    file_type      TEXT    NOT NULL CHECK (file_type IN ('PHOTO', 'VIDEO', 'AUDIO', 'DOCUMENT', 'OTHER')),
    source         TEXT    NOT NULL CHECK (source IN ('ANDROID_SYNC', 'WEB_UPLOAD', 'DISK_IMPORT')),
    backup_status  TEXT    NOT NULL DEFAULT 'PENDING'
                           CHECK (backup_status IN ('PENDING', 'SYNCED', 'FAILED', 'SKIPPED')),
    backup_at      TEXT,
    created_at     TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL,
    deleted_at     TEXT,
    thumbnail_path TEXT,
    captured_at    TEXT,
    share          INTEGER NOT NULL DEFAULT 0 CHECK (share IN (0, 1))
);

INSERT INTO files_new (
    id, user_id, parent_path, file_name, relative_path, file_size, mime_type, sha256,
    file_type, source, backup_status, backup_at, created_at, updated_at, deleted_at,
    thumbnail_path, captured_at, share
)
SELECT
    id, user_id, parent_path, file_name, relative_path, file_size, mime_type, sha256,
    file_type, source, backup_status, backup_at, created_at, updated_at, deleted_at,
    thumbnail_path, captured_at, share
FROM files;

DROP TABLE files;
ALTER TABLE files_new RENAME TO files;

CREATE UNIQUE INDEX uk_files_user_path ON files (user_id, relative_path) WHERE deleted_at IS NULL;
CREATE INDEX idx_files_user_parent ON files (user_id, parent_path);
CREATE INDEX idx_files_sha256 ON files (user_id, sha256);
CREATE INDEX idx_files_backup ON files (backup_status) WHERE deleted_at IS NULL;
CREATE INDEX idx_files_thumbnail ON files (thumbnail_path) WHERE thumbnail_path IS NOT NULL;
CREATE INDEX idx_files_user_captured ON files (user_id, captured_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_files_share_type_time ON files (file_type, share, captured_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_files_user_share ON files (user_id, share) WHERE deleted_at IS NULL;

PRAGMA foreign_keys = ON;
