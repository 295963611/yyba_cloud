-- 媒体直链代理端口（图片/视频缩略图与原文件）
INSERT OR IGNORE INTO app_config (config_key, config_value, description, updated_at)
VALUES ('server.media_port', '8081', '媒体代理端口（图片/视频直链）', datetime('now'));
