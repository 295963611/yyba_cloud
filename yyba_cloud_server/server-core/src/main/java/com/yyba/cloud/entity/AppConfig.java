package com.yyba.cloud.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 应用配置实体，对应表 {@code app_config}。
 * <p>键值对形式存储服务端运行参数，如存储路径、端口、分片大小、灾备开关等；由启动器或内部 API 维护。</p>
 */
@Getter
@Setter
@TableName("app_config")
public class AppConfig {

    /** 配置键，如 storage.root_path、backup.enabled */
    @TableId
    private String configKey;

    /** 配置值 */
    private String configValue;

    /** 配置项中文说明 */
    private String description;

    /** 最后修改时间 */
    private String updatedAt;
}
