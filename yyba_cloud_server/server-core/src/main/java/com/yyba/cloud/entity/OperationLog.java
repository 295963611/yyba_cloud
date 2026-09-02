package com.yyba.cloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 操作审计日志实体，对应表 {@code operation_logs}。
 * <p>记录登录、注册、上传、删除等关键操作，便于排查问题与安全审计。</p>
 */
@Getter
@Setter
@TableName("operation_logs")
public class OperationLog {

    /** 日志主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作用户 ID，系统操作可为空 */
    private Long userId;

    /** 操作类型，如 LOGIN、REGISTER、UPLOAD、DELETE */
    private String action;

    /** 操作目标路径或对象标识 */
    private String targetPath;

    /** 客户端 IP 地址 */
    private String clientIp;

    /** 附加详情（JSON 或文本） */
    private String detail;

    /** 操作发生时间 */
    private String createdAt;
}
