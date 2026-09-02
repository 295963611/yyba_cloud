package com.yyba.cloud.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public final class TimeUtil {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private TimeUtil() {
    }

    public static String now() {
        return ISO.format(Instant.now());
    }

    public static String plusHours(int hours) {
        return ISO.format(Instant.now().plus(hours, ChronoUnit.HOURS));
    }

    public static String fromEpochMs(long epochMs) {
        return ISO.format(Instant.ofEpochMilli(epochMs));
    }

    public static long toEpochMs(String iso) {
        if (iso == null || iso.isBlank()) {
            return 0L;
        }
        return Instant.parse(iso).toEpochMilli();
    }

    /** ISO 时间格式化为本地显示：yyyy-MM-dd HH:mm:ss */
    public static String toDisplay(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            return DISPLAY.format(Instant.parse(iso));
        } catch (Exception e) {
            return iso;
        }
    }
}
