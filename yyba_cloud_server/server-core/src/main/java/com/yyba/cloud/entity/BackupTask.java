package com.yyba.cloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 灾备任务实体，对应表 {@code backup_tasks}。
 * <p>文件上传完成后，若启用灾备，则将主存储文件异步复制到备份目录；本表记录复制任务状态。</p>
 */
@Getter
@Setter
@TableName("backup_tasks")
public class BackupTask {

    /** 任务主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联的 files 表记录 ID */
    private Long fileId;

    /** 文件所属用户 ID */
    private Long userId;

    /** 源文件绝对路径（主存储） */
    private String sourcePath;

    /** 目标文件绝对路径（灾备目录） */
    private String targetPath;

    /** 任务状态：PENDING / RUNNING / SUCCESS / FAILED */
    private String status = "PENDING";

    /** 失败重试次数 */
    private Integer retryCount = 0;

    /** 失败原因 */
    private String errorMessage;

    /** 任务创建时间 */
    private String createdAt;

    /** 任务完成时间 */
    private String completedAt;
}
