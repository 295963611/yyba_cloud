package com.yyba.cloud.util;



import android.content.ContentResolver;

import android.content.Context;

import android.net.Uri;

import android.os.Build;

import android.provider.MediaStore;



import androidx.activity.result.IntentSenderRequest;

import androidx.annotation.NonNull;

import androidx.annotation.Nullable;



import java.util.ArrayList;

import java.util.Collections;

import java.util.List;



/**

 * 从系统相册物理删除本地媒体（不触碰云端）。

 * <p>Android 11+ 使用 {@link MediaStore#createDeleteRequest} 批量授权；

 * Android 10 使用 {@link android.app.RecoverableSecurityException} 单条授权。</p>

 */

public final class LocalMediaDeleter {



    public static final int BATCH_DELETE_SIZE = 25;



    public enum Result {

        DELETED,

        NEEDS_USER_CONFIRMATION,

        FAILED

    }



    public enum StepType {

        FINISHED,

        REQUEST_CONFIRM,

        FAILED,

        UNSUPPORTED

    }



    public static final class Step {

        public final StepType type;

        @Nullable

        public final IntentSenderRequest confirmRequest;

        @NonNull

        public final List<Uri> confirmBatch;

        @NonNull

        public final List<Uri> deletedUris;



        private Step(StepType type, @Nullable IntentSenderRequest confirmRequest,

                     @NonNull List<Uri> confirmBatch, @NonNull List<Uri> deletedUris) {

            this.type = type;

            this.confirmRequest = confirmRequest;

            this.confirmBatch = confirmBatch;

            this.deletedUris = deletedUris;

        }



        static Step finished(List<Uri> deletedUris) {

            return new Step(StepType.FINISHED, null, List.of(), deletedUris);

        }



        static Step requestConfirm(IntentSenderRequest request, List<Uri> batch, List<Uri> deletedUris) {

            return new Step(StepType.REQUEST_CONFIRM, request, batch, deletedUris);

        }



        static Step failed(List<Uri> deletedUris) {

            return new Step(StepType.FAILED, null, List.of(), deletedUris);

        }



        static Step unsupported(List<Uri> deletedUris) {

            return new Step(StepType.UNSUPPORTED, null, List.of(), deletedUris);

        }

    }



    /**

     * 有状态删除流程：按 API 分批/串行请求系统授权，避免逐条 delete 触发大量 RSE。

     */

    public static final class DeleteFlow {

        private final Context appContext;

        private final List<Uri> allUris;

        private final List<Uri> deleted = new ArrayList<>();

        private int nextBatchStart;

        private final List<Uri> qPendingConfirm = new ArrayList<>();

        private int qConfirmIndex;



        public DeleteFlow(Context context, List<Uri> uris) {

            this.appContext = context.getApplicationContext();

            this.allUris = normalizeUris(uris);

            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {

                initAndroidQ();

            }

        }



        private void initAndroidQ() {

            for (Uri uri : allUris) {

                Result result = deleteUri(appContext, uri);

                if (result == Result.DELETED) {

                    deleted.add(uri);

                } else if (result == Result.NEEDS_USER_CONFIRMATION) {

                    qPendingConfirm.add(uri);

                }

            }

        }



        /** 启动或继续下一批/下一条系统确认。 */

        @NonNull

        public Step advance() {

            if (allUris.isEmpty()) {

                return Step.finished(deleted);

            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                return advanceAndroidR();

            }

            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {

                return advanceAndroidQ();

            }

            return advanceLegacy();

        }



        /** 用户确认系统删除弹窗后调用。 */

        public void onUserConfirmed(@NonNull List<Uri> batch) {

            if (batch.isEmpty()) {

                return;

            }

            deleted.addAll(batch);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                nextBatchStart += batch.size();

            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {

                qConfirmIndex++;

            }

        }



        @NonNull

        public List<Uri> getDeletedUris() {

            return Collections.unmodifiableList(deleted);

        }



        public int getTotalPlanned() {

            return allUris.size();

        }



        public int getDeletedCount() {

            return deleted.size();

        }



        private Step advanceAndroidR() {

            if (nextBatchStart >= allUris.size()) {

                return Step.finished(deleted);

            }

            int end = Math.min(nextBatchStart + BATCH_DELETE_SIZE, allUris.size());

            List<Uri> batch = new ArrayList<>(allUris.subList(nextBatchStart, end));

            IntentSenderRequest request = buildBatchDeleteRequest(appContext, batch);

            if (request == null) {

                return Step.failed(deleted);

            }

            return Step.requestConfirm(request, batch, deleted);

        }



        private Step advanceAndroidQ() {

            while (qConfirmIndex < qPendingConfirm.size()) {

                Uri uri = qPendingConfirm.get(qConfirmIndex);

                IntentSenderRequest request = buildRecoverableDeleteRequest(appContext, uri);

                if (request != null) {

                    return Step.requestConfirm(request, List.of(uri), deleted);

                }

                if (deleteUri(appContext, uri) == Result.DELETED) {

                    deleted.add(uri);

                    qConfirmIndex++;

                    continue;

                }

                return Step.failed(deleted);

            }

            return Step.finished(deleted);

        }



        private Step advanceLegacy() {

            for (Uri uri : allUris) {

                if (deleted.contains(uri)) {

                    continue;

                }

                if (deleteUri(appContext, uri) == Result.DELETED) {

                    deleted.add(uri);

                }

            }

            if (deleted.size() == allUris.size()) {

                return Step.finished(deleted);

            }

            return Step.unsupported(deleted);

        }

    }



    private LocalMediaDeleter() {

    }



    public static Result deleteUri(Context context, Uri uri) {

        if (context == null || uri == null) {

            return Result.FAILED;

        }

        ContentResolver resolver = context.getContentResolver();

        try {

            int rows = resolver.delete(uri, null, null);

            return rows > 0 ? Result.DELETED : Result.FAILED;

        } catch (SecurityException e) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

                    && e instanceof android.app.RecoverableSecurityException) {

                return Result.NEEDS_USER_CONFIRMATION;

            }

            return Result.FAILED;

        }

    }



    /** Android 11+ 批量删除需用户确认时使用 */

    @Nullable

    public static IntentSenderRequest buildBatchDeleteRequest(Context context, List<Uri> uris) {

        if (context == null || uris == null || uris.isEmpty()) {

            return null;

        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {

            return null;

        }

        try {

            android.app.PendingIntent pending = MediaStore.createDeleteRequest(

                    context.getContentResolver(), uris);

            return new IntentSenderRequest.Builder(pending.getIntentSender()).build();

        } catch (Exception e) {

            return null;

        }

    }



    /** Android 10 单条删除授权 */

    @Nullable

    private static IntentSenderRequest buildRecoverableDeleteRequest(Context context, Uri uri) {

        if (context == null || uri == null) {

            return null;

        }

        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) {

            return null;

        }

        ContentResolver resolver = context.getContentResolver();

        try {

            resolver.delete(uri, null, null);

            return null;

        } catch (SecurityException e) {

            if (e instanceof android.app.RecoverableSecurityException recoverable) {

                try {

                    return new IntentSenderRequest.Builder(

                            recoverable.getUserAction().getActionIntent().getIntentSender()).build();

                } catch (Exception ex) {

                    return null;

                }

            }

            return null;

        }

    }



    @NonNull

    private static List<Uri> normalizeUris(@Nullable List<Uri> uris) {

        List<Uri> normalized = new ArrayList<>();

        if (uris == null) {

            return normalized;

        }

        for (Uri uri : uris) {

            if (uri != null) {

                normalized.add(uri);

            }

        }

        return normalized;

    }



    public static List<Uri> parseUris(@Nullable List<String> localUris) {

        List<Uri> uris = new ArrayList<>();

        if (localUris == null) {

            return uris;

        }

        for (String raw : localUris) {

            if (raw == null || raw.isEmpty()) {

                continue;

            }

            uris.add(Uri.parse(raw));

        }

        return uris;

    }

}


