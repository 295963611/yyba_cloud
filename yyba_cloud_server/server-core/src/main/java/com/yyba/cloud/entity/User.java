package com.yyba.cloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户实体，对应表 {@code users}。
 * <p>存储家庭云盘登录账号、密码哈希、配额与启用状态。</p>
 */
@Getter
@Setter
@TableName("users")
public class User {

    /** 用户主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录用户名，全局唯一 */
    private String username;

    /** 密码哈希（BCrypt），不存明文 */
    private String passwordHash;

    /** 显示名称，默认同用户名 */
    private String displayName;

    /** 存储配额（字节），-1 表示不限制 */
    private Long storageQuotaBytes = -1L;

    /** 是否启用：1=启用，0=禁用 */
    private Integer enabled = 1;

    /** 角色：ADMIN 管理员 / MEMBER 普通成员 */
    private String role = "MEMBER";

    /** 创建时间（ISO 字符串） */
    private String createdAt;

    /** 最后更新时间 */
    private String updatedAt;
}
