package com.yyba.cloud.controller;

import com.yyba.cloud.dto.*;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.security.UserPrincipal;
import com.yyba.cloud.service.FileService;
import com.yyba.cloud.util.ContentDispositionUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import com.yyba.cloud.util.MediaTypeUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

/**
 * 网盘文件接口控制器。
 * <p>提供目录浏览、同步索引、文件下载与删除；操作 {@code files} 表及磁盘文件。</p>
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * 列出指定目录下的子文件夹与文件。
     *
     * @param path 相对路径，空字符串表示用户根目录
     */
    @GetMapping
    public ApiResponse<List<FileItemDto>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "") String path) {
        return ApiResponse.ok(fileService.listFiles(principal.getUser(), path));
    }

    /**
     * 相册按拍摄时间分页拉取云端照片（captured_at 严格小于 beforeTime）。
     */
    @GetMapping("/album-photos")
    public ApiResponse<AlbumPhotoPageDto> albumPhotos(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "PHOTO") String fileType,
            @RequestParam long beforeTime,
            @RequestParam(defaultValue = "48") int limit) {
        return ApiResponse.ok(fileService.listAlbumPhotos(principal.getUser(), fileType, beforeTime, limit));
    }

    /**
     * 家庭/我分享：分页查询已分享的照片或视频。
     */
    @GetMapping("/shared")
    public ApiResponse<AlbumPhotoPageDto> shared(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "family") String scope,
            @RequestParam(defaultValue = "PHOTO") String fileType,
            @RequestParam long beforeTime,
            @RequestParam(defaultValue = "48") int limit) {
        return ApiResponse.ok(fileService.listSharedMedia(
                principal.getUser(), scope, fileType, beforeTime, limit));
    }

    /** 单张或批量更新分享状态 */
    @PutMapping("/share")
    public ApiResponse<Map<String, Object>> share(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ShareBatchRequest request,
            HttpServletRequest httpRequest) {
        int updated = fileService.updateShareStatus(
                principal.getUser(), request.getPaths(), request.getShare(), httpRequest.getRemoteAddr());
        return ApiResponse.ok(Map.of("updated", updated));
    }

    /** 一键分享/取消分享某类型全部媒体 */
    @PutMapping("/share/all")
    public ApiResponse<Map<String, Object>> shareAll(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "PHOTO") String fileType,
            @RequestParam int share,
            HttpServletRequest httpRequest) {
        int updated = fileService.updateShareAll(
                principal.getUser(), fileType, share, httpRequest.getRemoteAddr());
        return ApiResponse.ok(Map.of("updated", updated));
    }

    @PostMapping("/reconcile")
    public ApiResponse<Map<String, Object>> reconcile(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "ALL") String category) {
        int indexed = fileService.reconcileDisk(principal.getUser(), category);
        return ApiResponse.ok(Map.of("indexed", indexed));
    }

    /** 批量校验云端文件是否仍有效（DB 未删且磁盘存在），供 Android 手机 Tab 校正已同步态 */
    @PostMapping("/verify-sync")
    public ApiResponse<VerifySyncResponse> verifySync(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody VerifySyncRequest request) {
        return ApiResponse.ok(fileService.verifySync(principal.getUser(), request));
    }

    /**
     * 获取同步索引（路径 + SHA-256 + 大小），供 Android 判断本地文件是否已上传。
     * <p>传 {@code since}（epoch ms，基于 updatedAt）时返回增量（新增/修改/删除）；</p>
     * <p>不传 since 时分页拉取全量 bootstrap（cursor + limit）。</p>
     */
    @GetMapping("/sync-index")
    public ApiResponse<SyncIndexPageDto> syncIndex(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "500") int limit) {
        return ApiResponse.ok(fileService.listSyncIndex(principal.getUser(), since, cursor, limit));
    }

    /**
     * 下载图片缩略图（JPEG）。
     *
     * @param path 原图相对路径
     */
    @GetMapping("/thumbnail")
    public ResponseEntity<Resource> thumbnail(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String path,
            @RequestParam(required = false) Long ownerId) {
        User user = principal.getUser();
        try {
            Resource resource = fileService.getThumbnailResource(user, path, ownerId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"thumb.jpg\"")
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(resource);
        } catch (IllegalArgumentException e) {
            try {
                Resource resource = fileService.getFileResource(user, path, ownerId);
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtil.inline(extractName(path)))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(resource);
            } catch (IllegalArgumentException ignored) {
                return ResponseEntity.notFound().build();
            }
        }
    }

    /**
     * 下载文件二进制内容。
     *
     * @param path 文件相对路径
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String path,
            @RequestParam(required = false) Long ownerId) throws Exception {
        User user = principal.getUser();
        Resource resource = fileService.getFileResource(user, path, ownerId);
        var builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtil.attachment(extractName(path)))
                .contentType(MediaTypeUtil.probeFromPath(path));
        long length = resource.contentLength();
        if (length > 0) {
            builder.contentLength(length);
        }
        return builder.body(resource);
    }

    /**
     * 移入回收站（保留磁盘文件至 .trash 目录）。
     */
    @DeleteMapping
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String path,
            HttpServletRequest request) {
        fileService.deleteFile(principal.getUser(), path, request.getRemoteAddr());
        return ApiResponse.ok("已移入回收站", null);
    }

    @GetMapping("/trash")
    public ApiResponse<List<FileItemDto>> trash(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(fileService.listTrash(principal.getUser()));
    }

    /**
     * 从回收站永久删除（不可恢复）。
     */
    @DeleteMapping("/trash")
    public ApiResponse<Void> permanentDeleteFromTrash(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String path,
            HttpServletRequest request) {
        fileService.permanentDeleteFromTrash(principal.getUser(), path, request.getRemoteAddr());
        return ApiResponse.ok("已永久删除", null);
    }

    @PutMapping("/restore")
    public ApiResponse<Void> restore(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String path,
            HttpServletRequest request) {
        fileService.restoreFile(principal.getUser(), path, request.getRemoteAddr());
        return ApiResponse.ok("已恢复", null);
    }

    private String extractName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
