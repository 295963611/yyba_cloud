package com.yyba.cloud.service;

import com.yyba.cloud.entity.User;
import com.yyba.cloud.util.HeicImageDecoder;
import com.yyba.cloud.util.PathUtil;
import com.yyba.cloud.util.VideoFrameExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

/**
 * 媒体缩略图生成服务。
 * <p>图片直接解码；视频通过 JAVE2 抽取首帧，存于 {@code .thumbs/{username}/} 目录。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailService {

    private static final int MAX_EDGE = 320;
    private static final List<String> PHOTO_EXT = List.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif");
    private static final List<String> VIDEO_EXT = List.of(
            "mp4", "mov", "mkv", "avi", "3gp", "webm", "m4v");

    private final ConfigService configService;

    /**
     * 为已落盘的图片生成缩略图，返回相对 storage 根目录的路径。
     */
    public String generatePhotoThumbnail(Path sourceFile, User user, String relativePath,
                                       String mimeType, String fileName) {
        if (!isPhoto(mimeType, fileName)) {
            return null;
        }
        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            return null;
        }
        try {
            BufferedImage source = ImageIO.read(sourceFile.toFile());
            if (source == null && HeicImageDecoder.isHeic(fileName, mimeType)) {
                source = HeicImageDecoder.read(sourceFile);
            }
            if (source == null) {
                return null;
            }
            BufferedImage thumb = scaleToFit(source, MAX_EDGE);
            String thumbRelative = buildThumbnailRelativePath(user.getUsername(), relativePath);
            Path thumbPath = configService.getStorageRoot().resolve(thumbRelative);
            Files.createDirectories(thumbPath.getParent());
            ImageIO.write(thumb, "jpg", thumbPath.toFile());
            return thumbRelative;
        } catch (IOException e) {
            log.warn("生成缩略图失败: {}", relativePath, e);
            return null;
        } catch (Exception e) {
            log.warn("生成缩略图失败: {}", relativePath, e);
            return null;
        }
    }

    /** 为已落盘的视频生成封面缩略图（首帧），返回相对 storage 根目录的路径。 */
    public String generateVideoThumbnail(Path sourceFile, User user, String relativePath,
                                         String mimeType, String fileName) {
        if (!isVideo(mimeType, fileName)) {
            return null;
        }
        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            return null;
        }
        try {
            BufferedImage source = VideoFrameExtractor.extractFirstFrame(sourceFile);
            if (source == null) {
                return null;
            }
            BufferedImage thumb = scaleToFit(source, MAX_EDGE);
            String thumbRelative = buildThumbnailRelativePath(user.getUsername(), relativePath);
            Path thumbPath = configService.getStorageRoot().resolve(thumbRelative);
            Files.createDirectories(thumbPath.getParent());
            ImageIO.write(thumb, "jpg", thumbPath.toFile());
            return thumbRelative;
        } catch (IOException e) {
            log.warn("生成视频封面失败: {}", relativePath, e);
            return null;
        } catch (Exception e) {
            log.warn("生成视频封面失败: {}", relativePath, e);
            return null;
        }
    }

    public boolean isVideo(String mimeType, String fileName) {
        if (mimeType != null) {
            String lower = mimeType.toLowerCase();
            if (lower.startsWith("video/")) {
                return true;
            }
        }
        String ext = FilenameUtils.getExtension(fileName);
        return ext != null && VIDEO_EXT.contains(ext.toLowerCase());
    }

    public boolean isPhoto(String mimeType, String fileName) {
        if (mimeType != null) {
            String lower = mimeType.toLowerCase();
            if (lower.startsWith("image/")) {
                return true;
            }
            if (lower.contains("heic") || lower.contains("heif")) {
                return true;
            }
        }
        String ext = FilenameUtils.getExtension(fileName);
        return ext != null && PHOTO_EXT.contains(ext.toLowerCase());
    }

    public String buildThumbnailRelativePath(String username, String relativePath) {
        String hash = shortHash(relativePath);
        String base = FilenameUtils.getBaseName(PathUtil.normalizeRelative(relativePath));
        String safeBase = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeBase.length() > 40) {
            safeBase = safeBase.substring(0, 40);
        }
        return ".thumbs/" + username + "/" + hash + "_" + safeBase + ".jpg";
    }

    private BufferedImage scaleToFit(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return source;
        }
        double scale = Math.min((double) maxEdge / width, (double) maxEdge / height);
        if (scale >= 1.0) {
            return toRgb(source);
        }
        int targetW = Math.max(1, (int) Math.round(width * scale));
        int targetH = Math.max(1, (int) Math.round(height * scale));
        Image scaled = source.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
        BufferedImage output = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return output;
    }

    private BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
