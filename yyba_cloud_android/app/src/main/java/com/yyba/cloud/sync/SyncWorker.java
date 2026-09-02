package com.yyba.cloud.sync;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.util.PrefsManager;
import com.yyba.cloud.util.SessionManager;

import java.util.concurrent.TimeUnit;

public class SyncWorker extends Worker {

    public static final String WORK_NAME = "yyba_periodic_sync";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        PrefsManager prefs = new PrefsManager(getApplicationContext());
        if (!prefs.isAutoSync() || prefs.getToken() == null) {
            return Result.success();
        }
        ApiClient apiClient = new ApiClient(prefs);
        ApiClient.TokenValidation validation = apiClient.validateToken();
        if (validation != ApiClient.TokenValidation.VALID) {
            if (validation == ApiClient.TokenValidation.EXPIRED) {
                SessionManager.invalidateOnAuthFailure(getApplicationContext(), prefs);
            }
            return Result.success();
        }
        Intent intent = new Intent(getApplicationContext(), SyncForegroundService.class);
        intent.setAction(SyncForegroundService.ACTION_SYNC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(intent);
        } else {
            getApplicationContext().startService(intent);
        }
        return Result.success();
    }

    public static void schedule(Context context) {
        PrefsManager prefs = new PrefsManager(context);
        Constraints.Builder builder = new Constraints.Builder()
                .setRequiredNetworkType(prefs.isWifiOnly() ? NetworkType.UNMETERED : NetworkType.CONNECTED);
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                SyncWorker.class, 30, TimeUnit.MINUTES)
                .setConstraints(builder.build())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}
