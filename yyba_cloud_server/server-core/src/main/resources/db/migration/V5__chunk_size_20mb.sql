-- 上传分片大小改为 20MB
UPDATE app_config
SET config_value = '20971520',
    updated_at   = datetime('now')
WHERE config_key = 'upload.chunk_size';
