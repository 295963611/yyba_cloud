package com.yyba.cloud.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.mapper.UserMapper;
import com.yyba.cloud.service.ConfigService;
import com.yyba.cloud.service.DiskIndexAsyncService;
import com.yyba.cloud.service.DiskIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 服务启动后异步扫描各用户存储目录，补录磁盘上已有但未入库的文件。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DiskReconcileStartupListener {

    private final UserMapper userMapper;
    private final DiskIndexAsyncService diskIndexAsyncService;
    private final ConfigService configService;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void reconcileOnStartup() {
        try {
            configService.getStorageRoot();
        } catch (Exception e) {
            log.debug("跳过启动磁盘索引：{}", e.getMessage());
            return;
        }
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>().eq(User::getEnabled, 1));
        for (User user : users) {
            diskIndexAsyncService.reconcileUserMediaForceAsync(user.getId(), "ALL");
        }
    }
}
