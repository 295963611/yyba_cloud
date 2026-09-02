package com.yyba.cloud.service;

import com.yyba.cloud.config.SqliteWriteGuard;
import com.yyba.cloud.entity.OperationLog;
import com.yyba.cloud.mapper.OperationLogMapper;
import com.yyba.cloud.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 操作日志业务服务。
 * <p>向 {@code operation_logs} 表写入登录、上传、删除等审计记录。</p>
 */
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;
    private final SqliteWriteGuard sqliteWriteGuard;

    /** 记录一条操作审计日志 */
    public void log(Long userId, String action, String targetPath, String clientIp, String detail) {
        OperationLog log = new OperationLog();
        log.setUserId(userId);
        log.setAction(action);
        log.setTargetPath(targetPath);
        log.setClientIp(clientIp);
        log.setDetail(detail);
        log.setCreatedAt(TimeUtil.now());
        sqliteWriteGuard.write(() -> operationLogMapper.insert(log));
    }
}
