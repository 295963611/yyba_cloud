package com.yyba.cloud.controller;

import com.yyba.cloud.dto.*;
import com.yyba.cloud.security.UserPrincipal;
import com.yyba.cloud.service.UploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;

/**
 * 分片上传接口控制器。
 * <p>流程：初始化会话 → 逐片上传 → 查询进度 → 合并完成；对应 {@code upload_sessions} 表。</p>
 */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    /**
     * 初始化上传会话，返回 uploadId 与分片参数。
     */
    @PostMapping("/init")
    public ApiResponse<UploadInitResponse> init(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UploadInitRequest request) {
        return ApiResponse.ok(uploadService.initUpload(principal.getUser(), request));
    }

    /**
     * 上传单个分片（请求体为二进制数据）。
     */
    @PutMapping("/{uploadId}/chunk/{chunkIndex}")
    public ApiResponse<Void> uploadChunk(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String uploadId,
            @PathVariable int chunkIndex,
            HttpServletRequest request) throws Exception {
        try (InputStream in = request.getInputStream()) {
            uploadService.uploadChunk(principal.getUser(), uploadId, chunkIndex, in,
                    request.getContentLengthLong());
        }
        return ApiResponse.ok("分片上传成功", null);
    }

    /**
     * 查询上传进度与已完成的片序号。
     */
    @GetMapping("/{uploadId}/status")
    public ApiResponse<UploadStatusResponse> status(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String uploadId) {
        return ApiResponse.ok(uploadService.getStatus(principal.getUser(), uploadId));
    }

    /**
     * 合并分片为完整文件并写入 files 表。
     */
    @PostMapping("/{uploadId}/complete")
    public ApiResponse<UploadCompleteResponse> complete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String uploadId,
            HttpServletRequest request) {
        return ApiResponse.ok(uploadService.completeUpload(principal.getUser(), uploadId, request.getRemoteAddr()));
    }
}
