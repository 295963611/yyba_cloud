package com.yyba.cloud.util;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.List;

/** 支持 Range 的 inline 媒体响应，供 ExoPlayer 等播放器边下边播 */
public final class InlineResourceResponse {

    private InlineResourceResponse() {
    }

    public static ResponseEntity<ResourceRegion> serve(
            HttpServletRequest request, Resource resource, String path, MediaType mediaType) throws IOException {
        long contentLength = resource.contentLength();
        String disposition = ContentDispositionUtil.inline(MediaTypeUtil.extractFileName(path));
        List<HttpRange> ranges = HttpRange.parseRanges(request.getHeader(HttpHeaders.RANGE));

        // 必须使用可变 HttpHeaders；ResponseEntity.ok().header() 在 Spring 6 中为只读，
        // ResourceRegionHttpMessageConverter 写入时会 add Content-Range 导致 UnsupportedOperationException
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentType(mediaType);

        if (ranges.isEmpty()) {
            long regionLength = contentLength > 0 ? contentLength : Long.MAX_VALUE;
            if (contentLength > 0) {
                headers.setContentLength(contentLength);
            }
            return new ResponseEntity<>(new ResourceRegion(resource, 0, regionLength), headers, HttpStatus.OK);
        }

        if (contentLength <= 0) {
            return new ResponseEntity<>(new ResourceRegion(resource, 0, Long.MAX_VALUE), headers, HttpStatus.OK);
        }

        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(contentLength);
        long end = range.getRangeEnd(contentLength);
        long rangeLength = end - start + 1;
        headers.setContentLength(rangeLength);
        // CONTENT_RANGE 由 ResourceRegionHttpMessageConverter 根据 ResourceRegion 自动追加
        return new ResponseEntity<>(new ResourceRegion(resource, start, rangeLength), headers, HttpStatus.PARTIAL_CONTENT);
    }
}
