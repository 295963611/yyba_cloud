package com.yyba.cloud.util;

import android.app.Activity;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.yyba.cloud.R;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.ui.ImagePreviewActivity;
import com.yyba.cloud.ui.VideoPreviewActivity;

/**
 * 云端文件下载：网盘 Tab 保存到 Downloads；相册/视频保存到相册并绑定 SyncRecord。
 */
public final class CloudDownloadRunner {

    private CloudDownloadRunner() {
    }

    /** 网盘等场景：仅保存到 Downloads，不绑定相册同步 */
    public static void start(Fragment fragment, ApiClient apiClient,
                             String fileName, String relativePath, Long ownerId) {
        if (fragment == null || !fragment.isAdded()) {
            return;
        }
        start(fragment.requireActivity(), apiClient, fileName, relativePath, ownerId, null, false, null);
    }

    public static void start(Activity activity, ApiClient apiClient,
                             String fileName, String relativePath, Long ownerId) {
        start(activity, apiClient, fileName, relativePath, ownerId, null, false, null);
    }

    /** 相册/视频：保存到系统相册并绑定云端路径，合并为一条「已同步」 */
    public static void startGallerySync(Fragment fragment, ApiClient apiClient,
                                        String cloudRemotePath, Long ownerId,
                                        CloudDownloadCallback onSuccess) {
        startGallerySync(fragment, apiClient, cloudRemotePath, ownerId, null, onSuccess);
    }

    public static void startGallerySync(Activity activity, ApiClient apiClient,
                                        String cloudRemotePath, Long ownerId,
                                        CloudDownloadCallback onSuccess) {
        startGallerySync(activity, apiClient, cloudRemotePath, ownerId, null, onSuccess);
    }

    public static void startGallerySync(Fragment fragment, ApiClient apiClient,
                                        String cloudRemotePath, Long ownerId, Long remoteFileId,
                                        CloudDownloadCallback onSuccess) {
        if (fragment == null || !fragment.isAdded()) {
            return;
        }
        String fileName = CloudMediaDownloadHelper.fileNameFromPath(cloudRemotePath);
        start(fragment.requireActivity(), apiClient, fileName, cloudRemotePath, ownerId,
                remoteFileId, true, onSuccess);
    }

    public static void startGallerySync(Activity activity, ApiClient apiClient,
                                        String cloudRemotePath, Long ownerId, Long remoteFileId,
                                        CloudDownloadCallback onSuccess) {
        String fileName = CloudMediaDownloadHelper.fileNameFromPath(cloudRemotePath);
        start(activity, apiClient, fileName, cloudRemotePath, ownerId, remoteFileId, true, onSuccess);
    }

    public static void startGallerySync(Fragment fragment, ApiClient apiClient,
                                        String cloudRemotePath, Long ownerId,
                                        Runnable onSuccess) {
        startGallerySync(fragment, apiClient, cloudRemotePath, ownerId, null,
                (name, saved) -> {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                });
    }

    public static void startGallerySync(Activity activity, ApiClient apiClient,
                                        String cloudRemotePath, Long ownerId,
                                        Runnable onSuccess) {
        startGallerySync(activity, apiClient, cloudRemotePath, ownerId, null,
                (name, saved) -> {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                });
    }

    private static void start(Activity activity, ApiClient apiClient,
                              String fileName, String relativePath, Long ownerId,
                              Long remoteFileId, boolean gallerySync,
                              CloudDownloadCallback onSuccess) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (relativePath == null || relativePath.isEmpty() || fileName == null) {
            return;
        }
        if (gallerySync && !CloudMediaDownloadHelper.shouldBindToGallery(relativePath)) {
            gallerySync = false;
        }

        boolean inlineProgress = useInlineProgress(activity);
        View dialogView = LayoutInflater.from(activity).inflate(
                inlineProgress ? R.layout.overlay_download_progress : R.layout.dialog_download_progress,
                null);
        TextView tvName = dialogView.findViewById(R.id.downloadFileName);
        LinearProgressIndicator progressBar = dialogView.findViewById(R.id.downloadProgress);
        TextView tvPercent = dialogView.findViewById(R.id.downloadProgressText);
        tvName.setText(fileName);
        progressBar.setIndeterminate(true);

        AlertDialog dialog = null;
        View overlayRoot = null;
        if (inlineProgress) {
            View content = activity.findViewById(android.R.id.content);
            if (content instanceof FrameLayout frame) {
                overlayRoot = dialogView;
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM);
                frame.addView(overlayRoot, lp);
            }
        } else {
            dialog = new AlertDialog.Builder(activity)
                    .setView(dialogView)
                    .setCancelable(false)
                    .create();
            dialog.show();
        }

        final boolean bindGallery = gallerySync;
        final int[] lastPercent = {-1};
        final AlertDialog progressDialog = dialog;
        final View progressOverlay = overlayRoot;
        new Thread(() -> {
            try {
                String savedName;
                DownloadHelper.SavedMedia savedMedia = null;
                boolean alreadyOnDevice = false;
                boolean linkedExisting = false;
                if (bindGallery) {
                    CloudMediaDownloadHelper.DownloadResult result =
                            CloudMediaDownloadHelper.downloadBindAndSave(
                                    activity.getApplicationContext(),
                                    apiClient,
                                    relativePath,
                                    ownerId,
                                    remoteFileId,
                                    (bytesRead, totalBytes) -> postProgress(activity, progressDialog,
                                            progressOverlay, progressBar, tvPercent, lastPercent,
                                            bytesRead, totalBytes));
                    savedName = result.fileName;
                    savedMedia = result.saved;
                    alreadyOnDevice = result.alreadyOnDevice;
                    linkedExisting = result.linkedExisting;
                } else {
                    DownloadHelper.saveToDownloads(activity.getApplicationContext(), fileName,
                            out -> apiClient.downloadFileStream(relativePath, ownerId, out,
                                    (bytesRead, totalBytes) -> postProgress(activity, progressDialog,
                                            progressOverlay, progressBar, tvPercent, lastPercent,
                                            bytesRead, totalBytes)));
                    savedName = fileName;
                }
                final DownloadHelper.SavedMedia finalSaved = savedMedia;
                final boolean finalAlreadyOnDevice = alreadyOnDevice;
                final boolean finalLinkedExisting = linkedExisting;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) {
                        return;
                    }
                    hideProgress(progressDialog, progressOverlay);
                    int msgId;
                    if (bindGallery && finalAlreadyOnDevice) {
                        msgId = R.string.cloud_download_already_synced;
                    } else if (bindGallery && finalLinkedExisting) {
                        msgId = R.string.cloud_download_linked_existing;
                    } else if (bindGallery) {
                        msgId = R.string.cloud_download_sync_ok;
                    } else {
                        msgId = R.string.cloud_download_ok_name;
                    }
                    if (bindGallery && finalAlreadyOnDevice) {
                        Toast.makeText(activity, activity.getString(msgId), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(activity,
                                activity.getString(msgId, savedName),
                                Toast.LENGTH_SHORT).show();
                    }
                    if (onSuccess != null) {
                        onSuccess.onSuccess(savedName, finalSaved);
                    }
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) {
                        return;
                    }
                    hideProgress(progressDialog, progressOverlay);
                    Toast.makeText(activity,
                            activity.getString(R.string.cloud_download_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private static boolean useInlineProgress(Activity activity) {
        return activity instanceof ImagePreviewActivity
                || activity instanceof VideoPreviewActivity;
    }

    private static void hideProgress(AlertDialog dialog, View overlay) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        if (overlay != null && overlay.getParent() instanceof ViewGroup parent) {
            parent.removeView(overlay);
        }
    }

    private static void postProgress(Activity activity, AlertDialog dialog, View overlay,
                                     LinearProgressIndicator progressBar, TextView tvPercent,
                                     int[] lastPercent, long bytesRead, long totalBytes) {
        if (totalBytes <= 0) {
            return;
        }
        int pct = (int) Math.min(100, bytesRead * 100 / totalBytes);
        if (pct == lastPercent[0]) {
            return;
        }
        lastPercent[0] = pct;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) {
                return;
            }
            if (dialog != null && !dialog.isShowing() && overlay == null) {
                return;
            }
            if (overlay != null && overlay.getParent() == null) {
                return;
            }
            progressBar.setIndeterminate(false);
            progressBar.setProgressCompat(pct, true);
            tvPercent.setText(activity.getString(R.string.upload_progress, pct));
        });
    }
}
