package com.yyba.cloud.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.yyba.cloud.R;
import com.yyba.cloud.sync.SyncForegroundService;
import com.yyba.cloud.sync.SyncProgressBus;
import com.yyba.cloud.util.PermissionHelper;
import com.yyba.cloud.util.PrefsManager;
import com.yyba.cloud.util.ServerAddress;
import com.yyba.cloud.util.SyncStatusHelper;
import com.yyba.cloud.util.TrashConfigHelper;

public class HomeFragment extends Fragment {

    private enum SyncUiMode {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING
    }

    private PrefsManager prefs;
    private TextView tvServer;
    private TextView tvUnsyncedPhotos;
    private TextView tvUnsyncedVideos;
    private TextView tvUnsyncedAudio;
    private TextView tvSyncStatus;
    private TextView tvSyncProgressDetail;
    private ProgressBar progressSync;
    private MaterialButton btnSync;
    private boolean pendingSync;
    private String pendingCategory;
    private String pendingTitle;
    private int statsGeneration;
    private long lastStatsRefreshAt;
    private SyncUiMode syncUiMode = SyncUiMode.IDLE;

    private final SyncProgressBus.Listener syncListener = (event, snapshot, synced, skipped, failed, message) -> {
        if (!isAdded()) {
            return;
        }
        switch (event) {
            case STARTED:
                applyRunningUi(snapshot);
                break;
            case PROGRESS:
                applyProgressUi(snapshot);
                break;
            case COMPLETED:
            case CANCELLED:
            case ERROR:
                applyIdleUi();
                refreshStats();
                break;
            default:
                break;
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = true;
                for (Boolean v : result.values()) {
                    if (!v) {
                        granted = false;
                        break;
                    }
                }
                if (!granted) {
                    Toast.makeText(requireContext(), "需要相册权限才能访问媒体", Toast.LENGTH_LONG).show();
                    pendingSync = false;
                    pendingCategory = null;
                    applyIdleUi();
                    return;
                }
                if (pendingSync) {
                    pendingSync = false;
                    startSync();
                } else if (pendingCategory != null) {
                    doOpenMediaList(pendingCategory, pendingTitle);
                    pendingCategory = null;
                    pendingTitle = null;
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = new PrefsManager(requireContext());

        TextView tvUsername = view.findViewById(R.id.tvUsername);
        tvServer = view.findViewById(R.id.tvServer);
        tvUnsyncedPhotos = view.findViewById(R.id.tvUnsyncedPhotos);
        tvUnsyncedVideos = view.findViewById(R.id.tvUnsyncedVideos);
        tvUnsyncedAudio = view.findViewById(R.id.tvUnsyncedAudio);
        tvSyncStatus = view.findViewById(R.id.tvSyncStatus);
        tvSyncProgressDetail = view.findViewById(R.id.tvSyncProgressDetail);
        progressSync = view.findViewById(R.id.progressSync);
        btnSync = view.findViewById(R.id.btnSync);
        MaterialButton btnSettings = view.findViewById(R.id.btnSettings);
        MaterialButton btnLogout = view.findViewById(R.id.btnLogout);

        tvUsername.setText(prefs.getUsername());
        tvServer.setText(prefs.getServerDisplay());

        btnSync.setOnClickListener(v -> onSyncButtonClick());
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SettingsActivity.class)));
        btnLogout.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).logout();
            }
        });

        MaterialCardView tileAlbum = view.findViewById(R.id.tileAlbum);
        MaterialCardView tileVideo = view.findViewById(R.id.tileVideo);
        MaterialCardView tileDocument = view.findViewById(R.id.tileDocument);
        MaterialCardView tileAudio = view.findViewById(R.id.tileAudio);
        MaterialButton btnTrash = view.findViewById(R.id.btnTrash);

        tileAlbum.setOnClickListener(v -> openMediaList("IMAGE", getString(R.string.category_album)));
        tileVideo.setOnClickListener(v -> openMediaList("VIDEO", getString(R.string.category_video)));
        tileDocument.setOnClickListener(v -> openMediaList("DOCUMENT", getString(R.string.category_document)));
        tileAudio.setOnClickListener(v -> openMediaList("AUDIO", getString(R.string.category_audio)));
        btnTrash.setOnClickListener(v -> openTrash());

        TrashConfigHelper.prefetch(requireContext());
        refreshStats();
        if (SyncProgressBus.isRunning()) {
            applyRunningUi(SyncProgressBus.current());
        } else {
            applyIdleUi();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        SyncProgressBus.addListener(syncListener);
        if (SyncProgressBus.isRunning()) {
            applyRunningUi(SyncProgressBus.current());
        }
    }

    @Override
    public void onStop() {
        SyncProgressBus.removeListener(syncListener);
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        long now = System.currentTimeMillis();
        if (now - lastStatsRefreshAt > 60_000 && !SyncProgressBus.isRunning()) {
            refreshStats();
        }
    }

    public void updateServerUrl(String url) {
        if (tvServer != null && url != null && !url.isEmpty()) {
            ServerAddress.Parsed parsed = ServerAddress.parse(url);
            tvServer.setText(ServerAddress.formatDisplay(parsed.host, parsed.port));
        }
    }

    @Override
    public void onDestroyView() {
        statsGeneration++;
        tvServer = null;
        tvUnsyncedPhotos = null;
        tvUnsyncedVideos = null;
        tvUnsyncedAudio = null;
        tvSyncStatus = null;
        tvSyncProgressDetail = null;
        progressSync = null;
        btnSync = null;
        super.onDestroyView();
    }

    private void onSyncButtonClick() {
        if (syncUiMode == SyncUiMode.STARTING || syncUiMode == SyncUiMode.STOPPING) {
            return;
        }
        if (SyncProgressBus.isRunning() || syncUiMode == SyncUiMode.RUNNING) {
            applyStoppingUi();
            stopSync();
            return;
        }
        applyStartingUi();
        requestPermissionsAndSync();
    }

    private void openMediaList(String category, String title) {
        if (!PermissionHelper.hasPermissionForCategory(requireContext(), category)) {
            pendingCategory = category;
            pendingTitle = title;
            permissionLauncher.launch(PermissionHelper.permissionsForCategory(category));
            return;
        }
        doOpenMediaList(category, title);
    }

    private void doOpenMediaList(String category, String title) {
        MediaTabFragment.prewarmBeforeOpen(requireContext(), category);
        Intent intent = new Intent(requireContext(), MediaListActivity.class);
        intent.putExtra(MediaListActivity.EXTRA_CATEGORY, category);
        intent.putExtra(MediaListActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }

    private void openTrash() {
        startActivity(new Intent(requireContext(), TrashActivity.class));
    }

    private void refreshStats() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        final Context appContext = ctx.getApplicationContext();
        final int generation = ++statsGeneration;
        lastStatsRefreshAt = System.currentTimeMillis();
        new Thread(() -> {
            int[] counts = SyncStatusHelper.countHomeUnsynced(appContext);
            if (generation != statsGeneration || !isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (generation != statsGeneration || !isAdded()
                        || tvUnsyncedPhotos == null || tvUnsyncedVideos == null
                        || tvUnsyncedAudio == null) {
                    return;
                }
                tvUnsyncedPhotos.setText(getString(R.string.unsynced_photos, counts[0]));
                tvUnsyncedVideos.setText(getString(R.string.unsynced_videos, counts[1]));
                tvUnsyncedAudio.setText(getString(R.string.unsynced_audio, counts[2]));
            });
        }).start();
    }

    private void requestPermissionsAndSync() {
        if (!PermissionHelper.hasMediaPermission(requireContext())) {
            pendingSync = true;
            permissionLauncher.launch(PermissionHelper.mediaPermissions());
            return;
        }
        startSync();
    }

    private void startSync() {
        if (SyncProgressBus.isRunning()) {
            return;
        }
        Intent intent = new Intent(requireContext(), SyncForegroundService.class);
        intent.setAction(SyncForegroundService.ACTION_SYNC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent);
        } else {
            requireContext().startService(intent);
        }
        Toast.makeText(requireContext(), R.string.sync_started_toast, Toast.LENGTH_SHORT).show();
    }

    private void stopSync() {
        Intent intent = new Intent(requireContext(), SyncForegroundService.class);
        intent.setAction(SyncForegroundService.ACTION_CANCEL);
        requireContext().startService(intent);
    }

    private void applyRunningUi(SyncProgressBus.Snapshot snapshot) {
        syncUiMode = SyncUiMode.RUNNING;
        if (btnSync != null) {
            btnSync.setEnabled(true);
            btnSync.setText(R.string.sync_stop);
            btnSync.setIconResource(R.drawable.ic_sync);
        }
        if (tvSyncStatus != null) {
            tvSyncStatus.setText(R.string.sync_running);
        }
        applyProgressUi(snapshot);
    }

    private void applyStartingUi() {
        syncUiMode = SyncUiMode.STARTING;
        if (btnSync != null) {
            btnSync.setEnabled(false);
            btnSync.setText(R.string.sync_starting);
            btnSync.setIconResource(R.drawable.ic_sync);
        }
        if (tvSyncStatus != null) {
            tvSyncStatus.setText(R.string.sync_starting);
        }
        if (progressSync != null) {
            progressSync.setIndeterminate(true);
            progressSync.setVisibility(View.VISIBLE);
        }
        if (tvSyncProgressDetail != null) {
            tvSyncProgressDetail.setVisibility(View.GONE);
            tvSyncProgressDetail.setText("");
        }
    }

    private void applyStoppingUi() {
        syncUiMode = SyncUiMode.STOPPING;
        if (btnSync != null) {
            btnSync.setEnabled(false);
            btnSync.setText(R.string.sync_stopping);
            btnSync.setIconResource(R.drawable.ic_sync);
        }
        if (tvSyncStatus != null) {
            tvSyncStatus.setText(R.string.sync_stopping);
        }
        if (progressSync != null) {
            progressSync.setIndeterminate(true);
            progressSync.setVisibility(View.VISIBLE);
        }
        if (tvSyncProgressDetail != null) {
            tvSyncProgressDetail.setVisibility(View.GONE);
            tvSyncProgressDetail.setText("");
        }
    }

    private void applyProgressUi(SyncProgressBus.Snapshot snapshot) {
        if (snapshot == null || progressSync == null || tvSyncProgressDetail == null) {
            return;
        }
        if (snapshot.total <= 0) {
            if (syncUiMode == SyncUiMode.RUNNING || syncUiMode == SyncUiMode.STARTING) {
                progressSync.setIndeterminate(true);
                progressSync.setVisibility(View.VISIBLE);
            } else if (syncUiMode != SyncUiMode.STOPPING) {
                progressSync.setIndeterminate(false);
                progressSync.setVisibility(View.GONE);
            }
            tvSyncProgressDetail.setVisibility(View.GONE);
            tvSyncProgressDetail.setText("");
            if (tvSyncStatus != null) {
                if (syncUiMode == SyncUiMode.STOPPING) {
                    tvSyncStatus.setText(R.string.sync_stopping);
                } else if (syncUiMode == SyncUiMode.STARTING) {
                    tvSyncStatus.setText(R.string.sync_starting);
                } else {
                    tvSyncStatus.setText(R.string.sync_running);
                }
            }
            return;
        }
        progressSync.setIndeterminate(false);
        progressSync.setVisibility(View.VISIBLE);
        progressSync.setMax(snapshot.total);
        progressSync.setProgress(Math.max(0, snapshot.current));
        tvSyncProgressDetail.setVisibility(View.VISIBLE);
        if (tvSyncStatus != null) {
            tvSyncStatus.setText(getString(R.string.sync_progress_format,
                    snapshot.current, snapshot.total));
        }
        if (snapshot.fileName != null && !snapshot.fileName.isEmpty()) {
            tvSyncProgressDetail.setText(snapshot.fileName);
        } else {
            tvSyncProgressDetail.setText("");
        }
    }

    private void applyIdleUi() {
        syncUiMode = SyncUiMode.IDLE;
        if (btnSync != null) {
            btnSync.setEnabled(true);
            btnSync.setText(R.string.sync_now);
            btnSync.setIconResource(R.drawable.ic_sync);
        }
        if (tvSyncStatus != null) {
            tvSyncStatus.setText(R.string.sync_idle);
        }
        if (progressSync != null) {
            progressSync.setIndeterminate(false);
            progressSync.setVisibility(View.GONE);
            progressSync.setProgress(0);
        }
        if (tvSyncProgressDetail != null) {
            tvSyncProgressDetail.setVisibility(View.GONE);
            tvSyncProgressDetail.setText("");
        }
    }
}
