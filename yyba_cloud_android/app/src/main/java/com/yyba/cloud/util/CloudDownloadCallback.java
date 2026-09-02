package com.yyba.cloud.util;

@FunctionalInterface
public interface CloudDownloadCallback {
    void onSuccess(String savedName, DownloadHelper.SavedMedia savedMedia);
}
