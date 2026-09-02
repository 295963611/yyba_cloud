package com.yyba.cloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 云文件元数据实体，对应表 {@code files}。
 * <p>记录用户已上传文件的逻辑路径、大小、哈希、类型及灾备状态；实际文件存于磁盘。</p>
 */
@Getter
@Setter
@TableName("files")
public class CloudFile {

    /** 文件记录主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属用户 ID */
    private Long userId;

    /** 父目录相对路径，根目录为空字符串 */
    private String parentPath = "";

    /** 文件名（不含路径） */
    private String fileName;

    /** 用户空间内相对路径，如 photos/2024/01.jpg */
    private String relativePath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型，如 image/jpeg */
    private String mimeType;

    /** 文件 SHA-256，用于去重与 Android 同步比对 */
    private String sha256;

    /** 文件分类：PHOTO / VIDEO / AUDIO / DOCUMENT / OTHER */
    private String fileType;

    /** 来源：ANDROID_SYNC / WEB_UPLOAD / DISK_IMPORT（磁盘直放自动入库） */
    private String source;

    /** 灾备状态：PENDING / SYNCED / FAILED / SKIPPED */
    private String backupStatus = "PENDING";

    /** 灾备完成时间 */
    private String backupAt;

    /** 拍摄/采集时间（ISO-8601），相册时间轴排序用 */
    private String capturedAt;

    /** 入库时间 */
    private String createdAt;

    /** 最后更新时间 */
    private String updatedAt;

    /** 缩略图相对路径（相对 storage 根目录，如 .thumbs/user/xxx.jpg） */
    private String thumbnailPath;

    /** 是否分享到家庭：0=否，1=是 */
    private Integer share = 0;

    /** 软删除时间，非空表示已删除 */
    private String deletedAt;
}
