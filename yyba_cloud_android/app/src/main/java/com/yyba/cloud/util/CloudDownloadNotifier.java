package com.yyba.cloud.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 云端媒体下载绑定完成后，通知相册列表就地刷新（预览页未关闭时也可收到） */
public final class CloudDownloadNotifier {

    public interface Listener {
        void onCloudMediaDownloaded(String cloudRemotePath, DownloadHelper.SavedMedia saved);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private CloudDownloadNotifier() {
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static void notifyDownloaded(String cloudRemotePath, DownloadHelper.SavedMedia saved) {
        if (cloudRemotePath == null || cloudRemotePath.isEmpty()) {
            return;
        }
        for (Listener listener : LISTENERS) {
            listener.onCloudMediaDownloaded(cloudRemotePath, saved);
        }
    }
}
