package com.yyba.cloud.util;



import java.util.Collections;

import java.util.LinkedHashMap;

import java.util.Map;



/** 相册/视频 Tab 待刷新的云端下载结果（预览页仍打开时由 Notifier 处理，返回后由此兜底） */

public final class PendingMediaRefresh {



    private static final Map<String, DownloadHelper.SavedMedia> DOWNLOADS = new LinkedHashMap<>();



    private PendingMediaRefresh() {

    }



    public static void markDownloaded(String cloudRemotePath, DownloadHelper.SavedMedia saved) {

        if (cloudRemotePath == null || cloudRemotePath.isEmpty()) {

            return;

        }

        synchronized (DOWNLOADS) {

            DOWNLOADS.put(cloudRemotePath, saved);

        }

    }



    /** @return 待刷新的下载结果副本，并清空队列 */

    public static Map<String, DownloadHelper.SavedMedia> consumeDownloaded() {

        synchronized (DOWNLOADS) {

            if (DOWNLOADS.isEmpty()) {

                return Collections.emptyMap();

            }

            Map<String, DownloadHelper.SavedMedia> copy = new LinkedHashMap<>(DOWNLOADS);

            DOWNLOADS.clear();

            return copy;

        }

    }

}

