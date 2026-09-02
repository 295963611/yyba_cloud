package com.yyba.cloud.util;

import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * HEIC/HEIF 解码，供缩略图生成在 ImageIO 不支持时使用。
 * <p>可选依赖（加入后可生成 HEIC 缩略图）：</p>
 * <pre>
 * &lt;repository&gt;
 *   &lt;id&gt;AsposeJavaRepo&lt;/id&gt;
 *   &lt;url&gt;https://releases.aspose.com/java/repo/&lt;/url&gt;
 * &lt;/repository&gt;
 * &lt;dependency&gt;
 *   &lt;groupId&gt;com.aspose&lt;/groupId&gt;
 *   &lt;artifactId&gt;openize-heic&lt;/artifactId&gt;
 *   &lt;version&gt;25.9&lt;/version&gt;
 * &lt;/dependency&gt;
 * </pre>
 */
@Slf4j
public final class HeicImageDecoder {

    private static final String IO_FILE_STREAM = "openize.io.IOFileStream";
    private static final String IO_MODE = "openize.io.IOMode";
    private static final String HEIC_IMAGE = "openize.heic.decoder.HeicImage";
    private static final String PIXEL_FORMAT = "openize.heic.decoder.PixelFormat";

    private HeicImageDecoder() {
    }

    public static boolean isHeic(String fileName, String mimeType) {
        if (mimeType != null) {
            String lower = mimeType.toLowerCase();
            if (lower.contains("heic") || lower.contains("heif")) {
                return true;
            }
        }
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    public static BufferedImage read(Path path) {
        try {
            Class<?> modeClass = Class.forName(IO_MODE);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object readMode = Enum.valueOf((Class<Enum>) modeClass, "READ");

            Class<?> fsClass = Class.forName(IO_FILE_STREAM);
            Object fs = fsClass.getConstructor(String.class, modeClass)
                    .newInstance(path.toString(), readMode);

            Class<?> heicClass = Class.forName(HEIC_IMAGE);
            Object image = heicClass.getMethod("load", Class.forName("openize.io.Stream")).invoke(null, fs);

            int width = toInt(heicClass.getMethod("getWidth").invoke(image));
            int height = toInt(heicClass.getMethod("getHeight").invoke(image));
            if (width <= 0 || height <= 0) {
                return null;
            }

            Class<?> pixelFormatClass = Class.forName(PIXEL_FORMAT);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object argb32 = Enum.valueOf((Class<Enum>) pixelFormatClass, "Argb32");

            Method getPixels = heicClass.getMethod("getInt32Array", pixelFormatClass);
            int[] pixels = (int[]) getPixels.invoke(image, argb32);
            if (pixels == null) {
                return null;
            }

            BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            rgb.setRGB(0, 0, width, height, pixels, 0, width);
            return rgb;
        } catch (ClassNotFoundException e) {
            log.debug("未安装 Openize HEIC 库，跳过 HEIC 解码: {}", path);
            return null;
        } catch (Exception e) {
            log.warn("HEIC 解码失败: {}", path, e);
            return null;
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
