package com.yyba.cloud.controller;

import com.yyba.cloud.entity.User;
import com.yyba.cloud.security.UserPrincipal;
import com.yyba.cloud.service.FileService;
import com.yyba.cloud.util.ContentDispositionUtil;
import com.yyba.cloud.util.InlineResourceResponse;
import com.yyba.cloud.util.MediaTypeUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 网盘资源静态直链（与 Web 同端口），供 img/video/audio/iframe 等 inline 展示。
 * <p>鉴权：JWT Header 或 query {@code token=}。</p>
 */
@RestController
@RequestMapping("/media")
@RequiredArgsConstructor
public class MediaController {

    private final FileService fileService;

    /** 网盘任意文件 inline 访问（图片、视频、音频、PDF、文本等） */
    @GetMapping("/file")
    public ResponseEntity<ResourceRegion> file(
            HttpServletRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String path,
            @RequestParam(required = false) Long ownerId) throws IOException {
        return serveInline(request, principal.getUser(), path, ownerId, false);
    }

    @GetMapping("/thumb")
    public ResponseEntity<?> thumb(
            HttpServletRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String path,
            @RequestParam(required = false) Long ownerId) throws IOException {
        User user = principal.getUser();
        try {
            Resource resource = fileService.getThumbnailResource(user, path, ownerId);
            return inlineResponse(resource, path, MediaType.IMAGE_JPEG);
        } catch (IllegalArgumentException e) {
            return serveInline(request, user, path, ownerId, false);
        }
    }

    @GetMapping("/raw")
    public ResponseEntity<ResourceRegion> raw(
            HttpServletRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String path,
            @RequestParam(required = false) Long ownerId) throws IOException {
        return serveInline(request, principal.getUser(), path, ownerId, false);
    }

    /** attachment 下载（仍走直链，Content-Disposition 为 attachment） */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String path,
            @RequestParam(required = false) Long ownerId) throws Exception {
        User user = principal.getUser();
        Resource resource = fileService.getFileResource(user, path, ownerId);
        String name = MediaTypeUtil.extractFileName(path);
        var builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtil.attachment(name))
                .contentType(MediaTypeUtil.probeFromPath(path));
        long length = resource.contentLength();
        if (length > 0) {
            builder.contentLength(length);
        }
        return builder.body(resource);
    }

    private ResponseEntity<ResourceRegion> serveInline(
            HttpServletRequest request, User user, String path, Long ownerId, boolean attachment)
            throws IOException {
        Resource resource = fileService.getFileResource(user, path, ownerId);
        MediaType mediaType = MediaTypeUtil.probeFromPath(path);
        if (attachment) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDispositionUtil.attachment(MediaTypeUtil.extractFileName(path)));
            headers.setContentType(mediaType);
            long length = resource.contentLength();
            if (length > 0) {
                headers.setContentLength(length);
            }
            long regionLength = length > 0 ? length : Long.MAX_VALUE;
            return new ResponseEntity<>(new ResourceRegion(resource, 0, regionLength), headers, HttpStatus.OK);
        }
        return InlineResourceResponse.serve(request, resource, path, mediaType);
    }

    private ResponseEntity<Resource> inlineResponse(Resource resource, String path, MediaType mediaType) {
        long length = -1;
        try {
            length = resource.contentLength();
        } catch (IOException ignored) {
        }
        var builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDispositionUtil.inline(MediaTypeUtil.extractFileName(path)))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(mediaType);
        if (length > 0) {
            builder.contentLength(length);
        }
        return builder.body(resource);
    }
}
