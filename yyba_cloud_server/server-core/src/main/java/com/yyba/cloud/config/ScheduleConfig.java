package com.yyba.cloud.config;

import com.yyba.cloud.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScheduleConfig {

    private final UploadService uploadService;

    @Scheduled(fixedDelay = 3600000)
    public void cleanupExpiredUploads() {
        uploadService.cleanupExpiredSessions();
    }
}
