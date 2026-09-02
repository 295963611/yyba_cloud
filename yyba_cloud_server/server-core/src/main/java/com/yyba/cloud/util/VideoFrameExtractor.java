package com.yyba.cloud.util;

import lombok.extern.slf4j.Slf4j;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.ScreenExtractor;
import ws.schild.jave.process.ffmpeg.DefaultFFMPEGLocator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 通过 JAVE2（内置平台 ffmpeg 二进制）从视频抽取封面帧，无需系统安装 ffmpeg。
 */
@Slf4j
public final class VideoFrameExtractor {

    private static final double DEFAULT_SEEK_SEC = 1.0;
    /** qscale 1–31，数值越小质量越好 */
    private static final int THUMB_QUALITY = 2;
    private static volatile Boolean available;

    private VideoFrameExtractor() {
    }

    public static boolean isAvailable() {
        if (available != null) {
            return available;
        }
        synchronized (VideoFrameExtractor.class) {
            if (available != null) {
                return available;
            }
            try {
                new DefaultFFMPEGLocator().createExecutor().destroy();
                available = true;
            } catch (Exception e) {
                log.warn("JAVE2 ffmpeg 不可用，视频封面将跳过: {}", e.getMessage());
                available = false;
            }
            return available;
        }
    }

    /** 抽取指定秒数处的一帧；失败返回 null */
    public static BufferedImage extractFrame(Path videoFile, double seekSeconds) {
        if (!isAvailable() || videoFile == null || !Files.isRegularFile(videoFile)) {
            return null;
        }
        Path tempOut = null;
        try {
            tempOut = Files.createTempFile("xw-vthumb-", ".jpg");
            MultimediaObject source = new MultimediaObject(videoFile.toFile());
            ScreenExtractor extractor = new ScreenExtractor();
            long millis = (long) (Math.max(0, seekSeconds) * 1000L);
            extractor.renderOneImage(source, -1, -1, millis, tempOut.toFile(), THUMB_QUALITY);
            if (!Files.isRegularFile(tempOut) || Files.size(tempOut) == 0) {
                return null;
            }
            return ImageIO.read(tempOut.toFile());
        } catch (Exception e) {
            log.warn("JAVE2 抽帧失败: {}", videoFile, e);
            return null;
        } finally {
            if (tempOut != null) {
                try {
                    Files.deleteIfExists(tempOut);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static BufferedImage extractFirstFrame(Path videoFile) {
        BufferedImage frame = extractFrame(videoFile, DEFAULT_SEEK_SEC);
        if (frame == null) {
            frame = extractFrame(videoFile, 0);
        }
        return frame;
    }
}
