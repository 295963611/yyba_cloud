package com.yyba.cloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 分片上传会话实体，对应表 {@code upload_sessions}。
 * <p>跟踪单次上传的进度、分片信息、临时目录与过期时间；合并完成后写入 {@code files} 表。</p>
 */
@Getter
@Setter
@TableName("upload_sessions")
public class UploadSession {

    /** 上传会话 ID（UUID） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 发起上传的用户 ID */
    private Long userId;

    /** 原始文件名 */
    private String fileName;

    /** 目标相对路径 */
    private String relativePath;

    /** 文件总大小（字节） */
    private Long totalSize;

    /** 单个分片大小（字节） */
    private Integer chunkSize;

    /** 分片总数 */
    private Integer totalChunks;

    /** 已上传分片索引列表（JSON 数组，如 [0,1,2]） */
    private String uploadedChunks = "[]";

    /** MIME 类型 */
    private String mimeType;

    /** 客户端预计算的 SHA-256，用于秒传与校验 */
    private String sha256;

    /** 客户端拍摄时间（ISO-8601），Android 同步时传入 */
    private String capturedAt;

    /** 上传来源：ANDROID_SYNC 或 WEB_UPLOAD */
    private String source = "WEB_UPLOAD";

    /** 会话状态：INIT / UPLOADING / MERGING / COMPLETED / FAILED / EXPIRED */
    private String status = "INIT";

    /** 分片临时目录绝对路径 */
    private String tempDir;

    /** 失败时的错误信息 */
    private String errorMessage;

    /** 会话创建时间 */
    private String createdAt;

    /** 最后更新时间 */
    private String updatedAt;

    /** 会话过期时间，超时后可被清理 */
    private String expiresAt;
}
