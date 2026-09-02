-- 悠悠爸云盘 SQLite 初始表结构
-- users           用户账号
-- app_config      应用运行配置（键值对）
-- files           云文件元数据（逻辑目录 + 磁盘映射）
-- upload_sessions 分片上传进行中会话
-- backup_tasks    灾备复制任务队列
-- operation_logs  操作审计日志

CREATE TABLE users (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    username            TEXT    NOT NULL UNIQUE,
    password_hash       TEXT    NOT NULL,
    display_name        TEXT,
    storage_quota_bytes INTEGER NOT NULL DEFAULT -1,
    enabled             INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at          TEXT    NOT NULL,
    updated_at          TEXT    NOT NULL
);

CREATE INDEX idx_users_enabled ON users (enabled);

CREATE TABLE app_config (
    config_key   TEXT PRIMARY KEY,
    config_value TEXT NOT NULL,
    description  TEXT,
    updated_at   TEXT NOT NULL
);

INSERT INTO app_config (config_key, config_value, description, updated_at) VALUES
('server.port',                   '8080',           'HTTP 服务端口',           datetime('now')),
('storage.root_path',             '',               '主存储根目录',            datetime('now')),
('backup.enabled',                'false',          '是否启用灾备目录',        datetime('now')),
('backup.path',                   '',               '灾备目录路径',            datetime('now')),
('upload.chunk_size',             '4194304',        '上传分片大小（字节）',    datetime('now')),
('upload.session_expire_hours',   '24',             '上传会话过期小时数',      datetime('now')),
('jwt.expire_days',               '7',              'JWT 有效天数',            datetime('now'));

CREATE TABLE files (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id        INTEGER NOT NULL REFERENCES users(id),
    parent_path    TEXT    NOT NULL DEFAULT '',
    file_name      TEXT    NOT NULL,
    relative_path  TEXT    NOT NULL,
    file_size      INTEGER NOT NULL CHECK (file_size >= 0),
    mime_type      TEXT,
    sha256         TEXT,
    file_type      TEXT    NOT NULL CHECK (file_type IN ('PHOTO', 'VIDEO', 'DOCUMENT', 'OTHER')),
    source         TEXT    NOT NULL CHECK (source IN ('ANDROID_SYNC', 'WEB_UPLOAD')),
    backup_status  TEXT    NOT NULL DEFAULT 'PENDING'
                           CHECK (backup_status IN ('PENDING', 'SYNCED', 'FAILED', 'SKIPPED')),
    backup_at      TEXT,
    created_at     TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL,
    deleted_at     TEXT
);

CREATE UNIQUE INDEX uk_files_user_path ON files (user_id, relative_path) WHERE deleted_at IS NULL;
CREATE INDEX idx_files_user_parent ON files (user_id, parent_path);
CREATE INDEX idx_files_sha256 ON files (user_id, sha256);
CREATE INDEX idx_files_backup ON files (backup_status) WHERE deleted_at IS NULL;

CREATE TABLE upload_sessions (
    id               TEXT    PRIMARY KEY,
    user_id          INTEGER NOT NULL REFERENCES users(id),
    file_name        TEXT    NOT NULL,
    relative_path    TEXT    NOT NULL,
    total_size       INTEGER NOT NULL CHECK (total_size > 0),
    chunk_size       INTEGER NOT NULL CHECK (chunk_size > 0),
    total_chunks     INTEGER NOT NULL CHECK (total_chunks > 0),
    uploaded_chunks  TEXT    NOT NULL DEFAULT '[]',
    mime_type        TEXT,
    sha256           TEXT,
    source           TEXT    NOT NULL DEFAULT 'WEB_UPLOAD',
    status           TEXT    NOT NULL DEFAULT 'INIT'
                             CHECK (status IN ('INIT','UPLOADING','MERGING','COMPLETED','FAILED','EXPIRED')),
    temp_dir         TEXT,
    error_message    TEXT,
    created_at       TEXT    NOT NULL,
    updated_at       TEXT    NOT NULL,
    expires_at       TEXT    NOT NULL
);

CREATE INDEX idx_upload_user_status ON upload_sessions (user_id, status);
CREATE INDEX idx_upload_expires ON upload_sessions (expires_at) WHERE status != 'COMPLETED';

CREATE TABLE backup_tasks (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id       INTEGER NOT NULL REFERENCES files(id),
    user_id       INTEGER NOT NULL REFERENCES users(id),
    source_path   TEXT    NOT NULL,
    target_path   TEXT    NOT NULL,
    status        TEXT    NOT NULL DEFAULT 'PENDING'
                          CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    retry_count   INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    TEXT    NOT NULL,
    completed_at  TEXT
);

CREATE INDEX idx_backup_status ON backup_tasks (status);
CREATE INDEX idx_backup_file ON backup_tasks (file_id);

CREATE TABLE operation_logs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER REFERENCES users(id),
    action      TEXT    NOT NULL,
    target_path TEXT,
    client_ip   TEXT,
    detail      TEXT,
    created_at  TEXT    NOT NULL
);

CREATE INDEX idx_logs_user_time ON operation_logs (user_id, created_at DESC);
