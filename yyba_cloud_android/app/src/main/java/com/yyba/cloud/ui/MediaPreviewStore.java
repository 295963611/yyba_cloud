package com.yyba.cloud.ui;

import java.util.ArrayList;
import java.util.List;

/** 避免 Intent 传递大量 URI 导致 TransactionTooLargeException。 */
public final class MediaPreviewStore {

    private static List<String> localUris = new ArrayList<>();
    private static List<String> cloudPaths = new ArrayList<>();
    private static List<Long> cloudOwnerIds = new ArrayList<>();
    private static int startIndex;

    private MediaPreviewStore() {}

    public static void setLocalSession(List<String> uris, int index) {
        localUris = uris != null ? new ArrayList<>(uris) : new ArrayList<>();
        cloudPaths = new ArrayList<>();
        cloudOwnerIds = new ArrayList<>();
        startIndex = Math.max(0, index);
    }

    public static void setCloudSession(List<String> paths, List<Long> ownerIds, int index) {
        cloudPaths = paths != null ? new ArrayList<>(paths) : new ArrayList<>();
        cloudOwnerIds = ownerIds != null ? new ArrayList<>(ownerIds) : new ArrayList<>();
        localUris = new ArrayList<>();
        startIndex = Math.max(0, index);
    }

    public static void setCloudSession(List<String> paths, int index) {
        setCloudSession(paths, null, index);
    }

    public static List<String> getLocalUris() {
        return localUris;
    }

    public static List<String> getCloudPaths() {
        return cloudPaths;
    }

    public static List<Long> getCloudOwnerIds() {
        return cloudOwnerIds;
    }

    public static int getStartIndex() {
        return startIndex;
    }

    public static void clear() {
        localUris = new ArrayList<>();
        cloudPaths = new ArrayList<>();
        cloudOwnerIds = new ArrayList<>();
        startIndex = 0;
    }
}
