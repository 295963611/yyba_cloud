package com.yyba.cloud.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.app.Activity;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.paging.LoadState;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.yyba.cloud.R;
import com.yyba.cloud.api.AlbumPhotoItem;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.sync.MediaScanner;
import com.yyba.cloud.sync.SyncEngine;
import com.yyba.cloud.ui.ImagePreviewActivity;
import com.yyba.cloud.ui.MediaListActivity;
import com.yyba.cloud.ui.MediaListAdapter;
import com.yyba.cloud.ui.MediaPreviewStore;
import com.yyba.cloud.ui.VideoPreviewActivity;
import com.yyba.cloud.util.ApiErrorHelper;
import com.yyba.cloud.util.BackedUpCleanupHelper;
import com.yyba.cloud.util.LocalMediaDeleter;
import com.yyba.cloud.util.CloudDownloadNotifier;
import com.yyba.cloud.util.CloudDownloadRunner;
import com.yyba.cloud.util.CloudMediaDownloadHelper;
import com.yyba.cloud.util.DownloadHelper;
import com.yyba.cloud.util.SyncRecordLookup;
import com.yyba.cloud.util.LocalPhoneFirstPageCache;
import com.yyba.cloud.util.PendingMediaRefresh;
import com.yyba.cloud.util.PermissionHelper;
import com.yyba.cloud.util.PrefsManager;
import com.yyba.cloud.paging.MediaCategoryHelper;
import com.yyba.cloud.paging.MediaPagingFactory;
import com.yyba.cloud.paging.SharedLoadKey;
import com.yyba.cloud.util.ServerSyncCache;
import com.yyba.cloud.util.SyncVerifyScheduler;
import com.yyba.cloud.util.SharedMediaPrefetchCache;
import com.yyba.cloud.util.SyncStatusHelper;
import com.yyba.cloud.util.TrashConfigHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MediaTabFragment
extends Fragment
implements MediaListAdapter.Listener {
    private static final String ARG_CATEGORY = "category";
    private static final String ARG_TAB_TYPE = "tab_type";
    private static final int PAGE_SIZE = SyncVerifyScheduler.PAGE_SIZE;
    private String category;
    private String tabType;
    private ApiClient apiClient;
    private MediaListAdapter adapter;
    private MediaLoadStateAdapter loadStateAdapter;
    private ProgressBar progressLoading;
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView rvMedia;
    private final Map<String, SyncRecord> pageSyncRecords = new HashMap<>();
    private Map<String, Integer> shareByPath = Map.of();
    private final SyncVerifyScheduler syncVerifyScheduler = new SyncVerifyScheduler();
    private volatile boolean shareInProgress;
    @Nullable
    private LocalMediaDeleter.DeleteFlow activeDeleteFlow;
    @Nullable
    private Map<String, SyncRecord> activeDeleteRecordsByUri;
    private int activeDeleteLocalDone;
    private int activeClearRemoteOnlyCount;
    @Nullable
    private Runnable activeDeleteFinishUi;
    @Nullable
    private List<Uri> pendingConfirmBatch;
    private GridLayoutManager layoutManager;
    private boolean visibleLoadStarted;
    private boolean pagingInitialized;
    private boolean incompleteHashBackfillStarted;
    private Context appContext;
    private LiveData<PagingData<MediaListAdapter.MediaRow>> pagingLiveData;
    private final CloudDownloadNotifier.Listener cloudDownloadListener = (path, saved) -> runOnUiIfAdded(() -> applyAfterCloudDownload(path, saved));
    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
        boolean granted = true;
        for (Boolean value : result.values()) {
            if (!Boolean.TRUE.equals(value)) {
                granted = false;
                break;
            }
        }
        if (!granted) {
            Toast.makeText(requireContext(), "需要相应权限才能访问媒体文件", Toast.LENGTH_SHORT).show();
            return;
        }
        reload();
    });
    private final ActivityResultLauncher<Intent> videoPreviewLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getData() == null) {
            return;
        }
        String downloaded = result.getData().getStringExtra(ImagePreviewActivity.EXTRA_DOWNLOADED_CLOUD_PATH);
        if (downloaded != null) {
            applyAfterCloudDownload(downloaded, null);
            return;
        }
        if (result.getResultCode() != VideoPreviewActivity.RESULT_GHOST_FILE) {
            return;
        }
        String path = result.getData().getStringExtra(VideoPreviewActivity.EXTRA_CLOUD_PATH);
        if (path != null) {
            removeGhostCloudPath(path);
        }
    });
    private final ActivityResultLauncher<Intent> imagePreviewLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getData() == null) {
            return;
        }
        String path = result.getData().getStringExtra(ImagePreviewActivity.EXTRA_DOWNLOADED_CLOUD_PATH);
        if (path != null) {
            applyAfterCloudDownload(path, null);
        }
    });
    private final ActivityResultLauncher<IntentSenderRequest> deleteMediaLauncher = registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(), result -> {
        if (activeDeleteFlow == null) {
            return;
        }
        if (result.getResultCode() == Activity.RESULT_OK) {
            new Thread(this::onDeleteFlowUserConfirmed, "local-media-delete-confirm").start();
            return;
        }
        resetDeleteFlow();
        Toast.makeText(requireContext(), R.string.clean_backed_up_cancelled, Toast.LENGTH_SHORT).show();
    });

    public static MediaTabFragment newInstance(String category, String tabType) {
        MediaTabFragment fragment = new MediaTabFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CATEGORY, category);
        args.putString(ARG_TAB_TYPE, tabType);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_media_tab, container, false);
    }

    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = this.getArguments();
        this.category = args != null ? args.getString(ARG_CATEGORY, "IMAGE") : "IMAGE";
        this.tabType = args != null ? args.getString(ARG_TAB_TYPE, MediaListAdapter.TAB_PHONE) : MediaListAdapter.TAB_PHONE;
        this.swipeRefresh = view.findViewById(R.id.swipeRefresh);
        this.progressLoading = view.findViewById(R.id.progressLoading);
        this.rvMedia = view.findViewById(R.id.rvMedia);
        this.appContext = this.requireContext().getApplicationContext();
        this.apiClient = new ApiClient(new PrefsManager(this.appContext));
        this.adapter = new MediaListAdapter(this, this.apiClient, this.tabType);
        this.loadStateAdapter = new MediaLoadStateAdapter();
        this.layoutManager = new GridLayoutManager(this.requireContext(), 3);
        this.layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position >= adapter.getItemCount() ? 3 : 1;
            }
        });
        ConcatAdapter concatAdapter = adapter.withLoadStateFooter(loadStateAdapter);
        this.rvMedia.setLayoutManager(this.layoutManager);
        this.rvMedia.setHasFixedSize(true);
        this.rvMedia.setItemViewCacheSize(24);
        this.rvMedia.setAdapter(concatAdapter);
        this.adapter.addLoadStateListener(loadStates -> {
            LoadState refresh = loadStates.getRefresh();
            LoadState append = loadStates.getAppend();
            if (refresh instanceof LoadState.Loading) {
                if (adapter.getItemCount() == 0 && progressLoading != null) {
                    progressLoading.setVisibility(View.VISIBLE);
                }
            } else if (refresh instanceof LoadState.NotLoading) {
                if (progressLoading != null) {
                    progressLoading.setVisibility(View.GONE);
                }
                finishSwipeRefresh();
            } else if (refresh instanceof LoadState.Error) {
                if (progressLoading != null) {
                    progressLoading.setVisibility(View.GONE);
                }
                finishSwipeRefresh();
                Throwable error = ((LoadState.Error) refresh).getError();
                if (error != null && isAdded() && isCurrentTab()) {
                    Toast.makeText(requireContext(),
                            ApiErrorHelper.userMessage(requireContext(), error),
                            Toast.LENGTH_SHORT).show();
                }
            }
            if (append instanceof LoadState.Error) {
                Throwable error = ((LoadState.Error) append).getError();
                if (error != null && isAdded() && isCurrentTab()) {
                    Toast.makeText(requireContext(),
                            ApiErrorHelper.userMessage(requireContext(), error),
                            Toast.LENGTH_SHORT).show();
                }
            }
            return kotlin.Unit.INSTANCE;
        });
        this.rvMedia.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (!MediaTabFragment.this.isPhoneTab()) {
                    boolean scrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
                    MediaListAdapter.setScrollPaused(requireContext(), scrolling);
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        MediaTabFragment.this.adapter.refreshVisibleThumbnails(
                                MediaTabFragment.this.layoutManager);
                    }
                }
            }
        });
        this.swipeRefresh.setEnabled(true);
        this.swipeRefresh.setOnRefreshListener(() -> {
            if (isPhoneTab()) {
                LocalPhoneFirstPageCache.clear();
                pageSyncRecords.clear();
            }
            forceReload();
        });
        this.tryLoadWhenVisible();
    }

    private void initPagingIfNeeded() {
        if (pagingInitialized || appContext == null) {
            return;
        }
        pagingInitialized = true;
        PagingConfig config = new PagingConfig(
                MediaPagingFactory.PAGE_SIZE,
                6,
                false,
                MediaPagingFactory.PAGE_SIZE,
                PagingConfig.MAX_SIZE_UNBOUNDED,
                Integer.MIN_VALUE);
        if (isPhoneTab()) {
            Pager<Integer, MediaListAdapter.MediaRow> pager = new Pager<>(config,
                    () -> MediaPagingFactory.createPhone(appContext, category,
                            () -> shareByPath, this::onPhonePageLoaded));
            pagingLiveData = PagingLiveData.getLiveData(pager);
        } else if (isCloudTab()) {
            Pager<Long, MediaListAdapter.MediaRow> pager = new Pager<>(config,
                    () -> MediaPagingFactory.createCloud(appContext, apiClient, category));
            pagingLiveData = PagingLiveData.getLiveData(pager);
        } else {
            Pager<SharedLoadKey, MediaListAdapter.MediaRow> pager = new Pager<>(config,
                    () -> MediaPagingFactory.createShared(appContext, apiClient, category, tabType));
            pagingLiveData = PagingLiveData.getLiveData(pager);
        }
        pagingLiveData.observe(getViewLifecycleOwner(), data -> adapter.submitData(
                getViewLifecycleOwner().getLifecycle(), data));
    }

    private void onPhonePageLoaded(List<MediaListAdapter.MediaRow> rows, Map<String, SyncRecord> index) {
        if (index != null) {
            pageSyncRecords.putAll(index);
        }
        runOnUiIfAdded(() -> {
            adapter.seedOverlayFromPage(rows);
            runIncompleteHashBackfillIfNeeded();
            runPhoneSyncVerifyForRows(rows);
        });
    }

    private void runIncompleteHashBackfillIfNeeded() {
        if (!isPhoneTab() || incompleteHashBackfillStarted || adapter == null) {
            return;
        }
        Context app = requireAppContext();
        if (app == null || apiClient == null) {
            return;
        }
        incompleteHashBackfillStarted = true;
        new Thread(() -> {
            List<SyncRecord> incomplete = AppDatabase.getInstance(app).syncRecordDao()
                    .findIncompleteWithHash(SyncVerifyScheduler.PAGE_SIZE);
            if (incomplete == null || incomplete.isEmpty()) {
                return;
            }
            syncVerifyScheduler.verifyRecords(app, incomplete, apiClient, result -> {
                if (!isAdded()) {
                    return;
                }
                applyVerifyResult(result);
            });
        }, "sync-hash-backfill").start();
    }

    public void onDestroyView() {
        syncVerifyScheduler.cancel();
        resetDeleteFlow();
        pagingInitialized = false;
        pagingLiveData = null;
        visibleLoadStarted = false;
        super.onDestroyView();
    }

    private boolean isPhoneTab() {
        return MediaListAdapter.TAB_PHONE.equals(this.tabType);
    }

    private boolean isCloudTab() {
        return MediaListAdapter.TAB_CLOUD.equals(this.tabType);
    }

    private boolean isMyShareTab() {
        return MediaListAdapter.TAB_MY_SHARE.equals(this.tabType);
    }

    private void runOnUiIfAdded(Runnable action) {
        FragmentActivity activity = this.getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (this.isAdded()) {
                action.run();
            }
        });
    }

    private Context requireAppContext() {
        if (this.appContext != null) {
            return this.appContext;
        }
        Context ctx = this.getContext();
        if (ctx != null) {
            this.appContext = ctx.getApplicationContext();
        }
        return this.appContext;
    }

    public static void prewarmBeforeOpen(Context context, String category) {
        if (category == null) {
            return;
        }
        Context app = context.getApplicationContext();
        LocalPhoneFirstPageCache.prewarm(context, category);
    }

    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CloudDownloadNotifier.addListener(this.cloudDownloadListener);
    }

    public void onDestroy() {
        CloudDownloadNotifier.removeListener(this.cloudDownloadListener);
        super.onDestroy();
    }

    public void onResume() {
        super.onResume();
        if (this.visibleLoadStarted && this.isCurrentTab()) {
            Context cloudApp = this.requireAppContext();
            if (cloudApp != null && usesCloudDownloadOverlay()) {
                this.refreshDownloadStatusAsync(cloudApp);
            }
            Map<String, DownloadHelper.SavedMedia> downloaded = PendingMediaRefresh.consumeDownloaded();
            for (Map.Entry<String, DownloadHelper.SavedMedia> entry : downloaded.entrySet()) {
                this.applyAfterCloudDownload(entry.getKey(), entry.getValue());
            }
        }
        this.tryLoadWhenVisible();
    }

    public void loadWhenVisible() {
        this.tryLoadWhenVisible();
    }

    private void tryLoadWhenVisible() {
        if (!this.isAdded()) {
            return;
        }
        if (!isPhoneTab() && !this.isCurrentTab()) {
            return;
        }
        if (this.needsLocalMediaPermission() && !PermissionHelper.hasPermissionForCategory(this.requireContext(), this.category)) {
            permissionLauncher.launch(PermissionHelper.permissionsForCategory(category));
            return;
        }
        initPagingIfNeeded();
        if (!this.visibleLoadStarted) {
            this.visibleLoadStarted = true;
            if (isPhoneTab()) {
                shareByPath = ServerSyncCache.peekShareByPath();
            }
        }
    }

    private boolean needsLocalMediaPermission() {
        return isPhoneTab();
    }

    private boolean isCurrentTab() {
        FragmentActivity fragmentActivity = this.getActivity();
        if (!(fragmentActivity instanceof MediaListActivity)) {
            return true;
        }
        MediaListActivity activity = (MediaListActivity)fragmentActivity;
        return activity.getTabPositionForType(this.tabType) == activity.getCurrentTabPosition();
    }

    public String getTabType() {
        return this.tabType;
    }

    public boolean isSelectionMode() {
        return this.adapter.isSelectionMode();
    }

    public void clearSelection() {
        this.adapter.setSelectionMode(false);
        if (this.getActivity() instanceof MediaListActivity) {
            ((MediaListActivity)this.getActivity()).updateSelectionTitle(0);
        }
    }

    public void shareSelected() {
        List<String> paths = this.adapter.getSelectedUnsharedPaths();
        if (paths.isEmpty()) {
            Toast.makeText(requireContext(), R.string.share_need_sync, Toast.LENGTH_SHORT).show();
            return;
        }
        this.runShareAction(paths, 1);
    }

    public void unshareSelected() {
        List<String> paths = this.adapter.getSelectedSharedPaths();
        if (paths.isEmpty()) {
            return;
        }
        this.runShareAction(paths, 0);
    }

    public void deleteSelected() {
        if (isPhoneTab()) {
            List<MediaListAdapter.MediaRow> rows = this.adapter.getSelectedLocalRows();
            if (rows.isEmpty()) {
                Toast.makeText(requireContext(), R.string.phone_delete_local_none, Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(requireContext())
                    .setMessage(getString(R.string.phone_delete_local_confirm, rows.size()))
                    .setPositiveButton(R.string.delete, (d, w) -> runLocalDeleteSelected(rows))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        if (isCloudTab()) {
            List<String> paths = this.adapter.getSelectedCloudDeletePaths();
            if (paths.isEmpty()) {
                Toast.makeText(requireContext(), R.string.delete_none_cloud, Toast.LENGTH_SHORT).show();
                return;
            }
            int days = TrashConfigHelper.getRetentionDays(requireContext());
            new AlertDialog.Builder(requireContext())
                    .setMessage(getString(R.string.cloud_delete_batch_confirm, paths.size(), days))
                    .setPositiveButton(R.string.delete, (d, w) -> runCloudDeleteAction(paths))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    public void cleanBackedUp() {
        if (!isPhoneTab()) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setMessage(R.string.clean_backed_up_confirm1)
                .setPositiveButton(R.string.clean_backed_up, (d, w) -> startCleanBackedUpVerify())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startCleanBackedUpVerify() {
        Context app = requireAppContext();
        if (app == null) {
            return;
        }
        Toast.makeText(requireContext(), R.string.clean_backed_up_verifying, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<SyncRecord> candidates = BackedUpCleanupHelper.loadCandidates(app, category);
                if (candidates.isEmpty()) {
                    runOnUiIfAdded(() -> Toast.makeText(requireContext(),
                            R.string.clean_backed_up_none, Toast.LENGTH_SHORT).show());
                    return;
                }
                BackedUpCleanupHelper.Plan plan = BackedUpCleanupHelper.verifyAndPlan(apiClient, candidates);
                runOnUiIfAdded(() -> confirmCleanBackedUpPlan(plan));
            } catch (Exception e) {
                runOnUiIfAdded(() -> Toast.makeText(requireContext(),
                        ApiErrorHelper.userMessage(requireContext(), e), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void confirmCleanBackedUpPlan(BackedUpCleanupHelper.Plan plan) {
        if (plan.deleteLocally.isEmpty() && plan.clearRemoteOnly.isEmpty()) {
            Toast.makeText(requireContext(), R.string.clean_backed_up_none, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.clean_backed_up_confirm2,
                        plan.deleteLocally.size(), plan.clearRemoteOnly.size()))
                .setPositiveButton(R.string.clean_backed_up, (d, w) -> executeCleanBackedUpPlan(plan))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void executeCleanBackedUpPlan(BackedUpCleanupHelper.Plan plan) {
        Context app = requireAppContext();
        if (app == null) {
            return;
        }
        new Thread(() -> {
            BackedUpCleanupHelper.applyClearRemoteOnly(app, plan.clearRemoteOnly);
            List<Uri> uris = new ArrayList<>();
            List<SyncRecord> deleteRecords = new ArrayList<>();
            for (SyncRecord record : plan.deleteLocally) {
                if (record.localUri != null) {
                    uris.add(Uri.parse(record.localUri));
                    deleteRecords.add(record);
                }
            }
            startLocalMediaDeleteFlow(app, uris, deleteRecords, plan.clearRemoteOnly.size(), () -> {
                clearSelection();
                Toast.makeText(requireContext(),
                        getString(R.string.clean_backed_up_done,
                                activeDeleteLocalDone, activeClearRemoteOnlyCount),
                        Toast.LENGTH_LONG).show();
                forceReload();
            });
        }).start();
    }

    private void runLocalDeleteSelected(List<MediaListAdapter.MediaRow> rows) {
        Context app = requireAppContext();
        if (app == null || rows == null || rows.isEmpty()) {
            return;
        }
        new Thread(() -> {
            List<SyncRecord> records = new ArrayList<>();
            List<Uri> uris = new ArrayList<>();
            List<String> uriKeys = new ArrayList<>();
            for (MediaListAdapter.MediaRow row : rows) {
                if (row.item == null || row.item.uri == null) {
                    continue;
                }
                uris.add(row.item.uri);
                uriKeys.add(row.item.uri.toString());
            }
            Map<String, SyncRecord> recordsByUri = SyncRecordLookup.loadByLocalUris(app, uriKeys);
            for (MediaListAdapter.MediaRow row : rows) {
                if (row.item == null || row.item.uri == null) {
                    continue;
                }
                SyncRecord record = recordsByUri.get(row.item.uri.toString());
                if (record != null) {
                    records.add(record);
                } else {
                    SyncRecord stub = new SyncRecord();
                    stub.localUri = row.item.uri.toString();
                    records.add(stub);
                }
            }
            startLocalMediaDeleteFlow(app, uris, records, 0, () -> {
                clearSelection();
                Toast.makeText(requireContext(),
                        getString(R.string.phone_delete_local_ok, activeDeleteLocalDone),
                        Toast.LENGTH_SHORT).show();
                forceReload();
            });
        }).start();
    }

    private void startLocalMediaDeleteFlow(Context app, List<Uri> uris, List<SyncRecord> records,
                                           int clearRemoteOnlyCount, Runnable onUiComplete) {
        if (uris == null || uris.isEmpty()) {
            runOnUiIfAdded(onUiComplete);
            return;
        }
        Map<String, SyncRecord> byUri = new HashMap<>();
        for (SyncRecord record : records) {
            if (record != null && record.localUri != null) {
                byUri.put(record.localUri, record);
            }
        }
        activeDeleteFlow = new LocalMediaDeleter.DeleteFlow(app, uris);
        activeDeleteRecordsByUri = byUri;
        activeDeleteLocalDone = 0;
        activeClearRemoteOnlyCount = clearRemoteOnlyCount;
        activeDeleteFinishUi = onUiComplete;
        applyImmediateDeletedRecords(app, byUri, activeDeleteFlow);
        continueDeleteFlow(app);
    }

    private void applyImmediateDeletedRecords(Context app, Map<String, SyncRecord> byUri,
                                              LocalMediaDeleter.DeleteFlow flow) {
        List<SyncRecord> records = recordsForUris(byUri, flow.getDeletedUris());
        if (records.isEmpty()) {
            return;
        }
        BackedUpCleanupHelper.applyDeleteLocalRecords(app, records);
        activeDeleteLocalDone = records.size();
    }

    private static List<SyncRecord> recordsForUris(Map<String, SyncRecord> byUri, List<Uri> uris) {
        List<SyncRecord> records = new ArrayList<>();
        if (byUri == null || uris == null) {
            return records;
        }
        for (Uri uri : uris) {
            SyncRecord record = byUri.get(uri.toString());
            if (record != null) {
                records.add(record);
            }
        }
        return records;
    }

    private void continueDeleteFlow(Context app) {
        LocalMediaDeleter.DeleteFlow flow = activeDeleteFlow;
        if (flow == null || app == null) {
            return;
        }
        LocalMediaDeleter.Step step = flow.advance();
        switch (step.type) {
            case FINISHED -> {
                activeDeleteLocalDone = flow.getDeletedCount();
                finishDeleteFlow(app, false);
            }
            case REQUEST_CONFIRM -> {
                if (step.confirmRequest == null || step.confirmBatch.isEmpty()) {
                    runOnUiIfAdded(() -> {
                        Toast.makeText(requireContext(),
                                R.string.clean_backed_up_delete_failed, Toast.LENGTH_LONG).show();
                        resetDeleteFlow();
                    });
                    return;
                }
                pendingConfirmBatch = new ArrayList<>(step.confirmBatch);
                int processed = flow.getDeletedCount() + step.confirmBatch.size();
                runOnUiIfAdded(() -> {
                    Toast.makeText(requireContext(),
                            getString(R.string.clean_backed_up_delete_progress,
                                    Math.min(processed, flow.getTotalPlanned()),
                                    flow.getTotalPlanned()),
                            Toast.LENGTH_SHORT).show();
                    deleteMediaLauncher.launch(step.confirmRequest);
                });
            }
            case FAILED -> runOnUiIfAdded(() -> {
                Toast.makeText(requireContext(),
                        R.string.clean_backed_up_delete_failed, Toast.LENGTH_LONG).show();
                resetDeleteFlow();
            });
            case UNSUPPORTED -> runOnUiIfAdded(() -> {
                Toast.makeText(requireContext(),
                        R.string.clean_backed_up_delete_unsupported, Toast.LENGTH_LONG).show();
                resetDeleteFlow();
            });
        }
    }

    private void onDeleteFlowUserConfirmed() {
        Context app = requireAppContext();
        LocalMediaDeleter.DeleteFlow flow = activeDeleteFlow;
        Map<String, SyncRecord> byUri = activeDeleteRecordsByUri;
        List<Uri> batch = pendingConfirmBatch;
        pendingConfirmBatch = null;
        if (app == null || flow == null || byUri == null || batch == null || batch.isEmpty()) {
            runOnUiIfAdded(this::resetDeleteFlow);
            return;
        }
        flow.onUserConfirmed(batch);
        BackedUpCleanupHelper.applyDeleteLocalRecords(app, recordsForUris(byUri, batch));
        activeDeleteLocalDone = flow.getDeletedCount();
        continueDeleteFlow(app);
    }

    private void finishDeleteFlow(Context app, boolean cancelled) {
        Runnable ui = activeDeleteFinishUi;
        resetDeleteFlow();
        if (!cancelled && ui != null) {
            runOnUiIfAdded(ui);
        }
    }

    private void resetDeleteFlow() {
        activeDeleteFlow = null;
        activeDeleteRecordsByUri = null;
        activeDeleteLocalDone = 0;
        activeClearRemoteOnlyCount = 0;
        activeDeleteFinishUi = null;
        pendingConfirmBatch = null;
    }

    public void downloadSelected() {
        List<MediaListAdapter.DownloadTarget> targets = this.adapter.getSelectedDownloadTargets();
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), R.string.download_none_cloud, Toast.LENGTH_SHORT).show();
            return;
        }
        this.runDownloadAction(targets);
    }

    private void runDownloadAction(List<MediaListAdapter.DownloadTarget> targets) {
        int[] pending = new int[]{0};
        for (MediaListAdapter.DownloadTarget target : targets) {
            if (target.relativePath == null) continue;
            pending[0] = pending[0] + 1;
        }
        if (pending[0] == 0) {
            return;
        }
        int total = pending[0];
        int[] remaining = new int[]{total};
        for (MediaListAdapter.DownloadTarget target : targets) {
            if (target.relativePath == null) continue;
            if (CloudMediaDownloadHelper.shouldBindToGallery(target.relativePath)) {
                CloudDownloadRunner.startGallerySync((Fragment)this, this.apiClient, target.relativePath,
                        target.ownerId, target.fileId, (name, saved) -> this.runOnUiIfAdded(() -> {
                    this.applyAfterCloudDownload(target.relativePath, saved);
                    remaining[0] = remaining[0] - 1;
                    if (remaining[0] <= 0) {
                        this.clearSelection();
                    }
                }));
                continue;
            }
            String fileName = MediaTabFragment.fileNameFromPath(target.relativePath);
            CloudDownloadRunner.start(this, this.apiClient, fileName, target.relativePath, target.ownerId);
            remaining[0] = remaining[0] - 1;
            if (remaining[0] > 0) continue;
            this.clearSelection();
        }
    }

    private void applyAfterCloudDownload(String cloudRemotePath, DownloadHelper.SavedMedia saved) {
        if (cloudRemotePath == null || cloudRemotePath.isEmpty()) {
            return;
        }
        Context app = this.requireAppContext();
        if (app == null) {
            return;
        }
        new Thread(() -> {
            SyncRecord record = AppDatabase.getInstance(app).syncRecordDao().findByRemotePath(cloudRemotePath);
            if (record == null && saved != null && saved.uri != null) {
                record = AppDatabase.getInstance(app).syncRecordDao().findByLocalUri(saved.uri.toString());
            }
            SyncRecordLookup.DownloadPatch patch;
            if (record != null && record.localUri != null) {
                patch = new SyncRecordLookup.DownloadPatch(true, record.localUri);
            } else if (saved != null && saved.uri != null) {
                patch = new SyncRecordLookup.DownloadPatch(true, saved.uri.toString());
            } else {
                AlbumPhotoItem item = new AlbumPhotoItem();
                item.relativePath = cloudRemotePath;
                patch = SyncRecordLookup.loadForCloudPage(app, List.of(item))
                        .resolveDownload(item);
            }
            SyncRecord finalRecord = record;
            SyncRecordLookup.DownloadPatch finalPatch = patch;
            this.runOnUiIfAdded(() -> {
                if (finalRecord != null && finalRecord.localUri != null) {
                    pageSyncRecords.put(finalRecord.localUri, finalRecord);
                }
                if (isPhoneTab() && finalRecord != null) {
                    adapter.applySyncState(pageSyncRecords);
                }
                if (matchesDownloadCategory(cloudRemotePath)) {
                    HashMap<String, SyncRecordLookup.DownloadPatch> states = new HashMap<>();
                    for (MediaListAdapter.MediaRow row : adapter.getRows()) {
                        if (row == null || !row.isCloudRow() || row.cloudItem == null) {
                            continue;
                        }
                        if (cloudRemotePath.equals(row.cloudItem.relativePath)) {
                            states.put(row.stableKey(), finalPatch);
                        }
                    }
                    if (!states.isEmpty()) {
                        adapter.applyDownloadStates(states);
                    }
                }
            });
        }).start();
    }

    private boolean matchesDownloadCategory(String cloudRemotePath) {
        if ("VIDEO".equals(this.category)) {
            return cloudRemotePath.startsWith("videos/");
        }
        if ("AUDIO".equals(this.category)) {
            return cloudRemotePath.startsWith("audio/") || cloudRemotePath.startsWith("uploads/audio/");
        }
        if ("DOCUMENT".equals(this.category)) {
            return cloudRemotePath.startsWith("uploads/documents/");
        }
        if ("OTHER".equals(this.category)) {
            return cloudRemotePath.startsWith("uploads/other/");
        }
        return cloudRemotePath.startsWith("photos/");
    }

    private void runCloudDeleteAction(List<String> paths) {
        Context app = this.requireAppContext();
        new Thread(() -> {
            ArrayList<String> deletedPaths = new ArrayList<>();
            Exception lastError = null;
            for (String path : paths) {
                try {
                    this.apiClient.deleteFile(path);
                    deletedPaths.add(path);
                    ServerSyncCache.removePath(app, path);
                } catch (Exception e) {
                    lastError = e;
                }
            }
            Exception error = lastError;
            ArrayList<String> succeeded = new ArrayList<>(deletedPaths);
            this.runOnUiIfAdded(() -> {
                if (!succeeded.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.cloud_delete_ok, Toast.LENGTH_SHORT).show();
                    clearSelection();
                    forceReload();
                    if (getActivity() instanceof MediaListActivity activity) {
                        activity.reloadTab(MediaListAdapter.TAB_CLOUD);
                    }
                } else if (error != null) {
                    Toast.makeText(requireContext(),
                            getString(R.string.cloud_delete_failed, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    public void shareAll() {
        this.runShareAllAction(1);
    }

    public void unshareAll() {
        this.runShareAllAction(0);
    }

    private void runShareAction(List<String> paths, int share) {
        if (shareInProgress) {
            return;
        }
        shareInProgress = true;
        new Thread(() -> {
            try {
                int updated = this.apiClient.updateShare(paths, share);
                this.runOnUiIfAdded(() -> {
                    shareInProgress = false;
                    this.onShareCompleted(share, updated, paths);
                });
            }
            catch (Exception e) {
                this.runOnUiIfAdded(() -> {
                    shareInProgress = false;
                    Toast.makeText(
                            this.requireContext(),
                            ApiErrorHelper.userMessage(this.requireContext(), e),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void runShareAllAction(int share) {
        if (shareInProgress) {
            return;
        }
        shareInProgress = true;
        new Thread(() -> {
            try {
                int updated = this.apiClient.updateShareAll(
                        MediaCategoryHelper.fileTypeForApi(this.category), share);
                this.runOnUiIfAdded(() -> {
                    shareInProgress = false;
                    this.onShareCompleted(share, updated, null);
                });
            }
            catch (Exception e) {
                this.runOnUiIfAdded(() -> {
                    shareInProgress = false;
                    Toast.makeText(
                            this.requireContext(),
                            ApiErrorHelper.userMessage(this.requireContext(), e),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void onShareCompleted(int share, int updated, List<String> paths) {
        if (updated <= 0) {
            Toast.makeText(requireContext(), R.string.share_none_updated, Toast.LENGTH_LONG).show();
            return;
        }
        int messageRes = share == 1 ? R.string.share_success : R.string.unshare_success;
        Toast.makeText(requireContext(), getString(messageRes, updated), Toast.LENGTH_SHORT).show();
        this.clearSelection();
        if (paths != null && !paths.isEmpty()) {
            Map<String, Integer> updatedShare = new HashMap<>(this.shareByPath);
            for (String path : paths) {
                updatedShare.put(path, share);
            }
            this.shareByPath = updatedShare;
            ServerSyncCache.applySharePaths(paths, share);
        }
        this.adapter.applyShareState(this.shareByPath);
        SharedMediaPrefetchCache.clear();
        FragmentActivity fragmentActivity = this.getActivity();
        if (fragmentActivity instanceof MediaListActivity) {
            MediaListActivity activity = (MediaListActivity)fragmentActivity;
            activity.reloadTab("my_share");
        }
    }

    public void reload() {
        forceReload();
    }

    public void forceReload() {
        initPagingIfNeeded();
        if (isPhoneTab()) {
            pageSyncRecords.clear();
        }
        if (adapter != null) {
            adapter.clearOverlayState();
            adapter.refresh();
        }
    }

    private void refreshDownloadStatusAsync(Context app) {
        if (app == null || adapter == null) {
            return;
        }
        List<String> stableKeys = new ArrayList<>();
        List<AlbumPhotoItem> cloudItems = new ArrayList<>();
        for (MediaListAdapter.MediaRow row : adapter.getRows()) {
            if (row != null && row.isCloudRow() && row.cloudItem != null) {
                stableKeys.add(row.stableKey());
                cloudItems.add(row.cloudItem);
            }
        }
        if (cloudItems.isEmpty()) {
            return;
        }
        new Thread(() -> {
            SyncRecordLookup.CloudPageLookup lookup = SyncRecordLookup.loadForCloudPage(app, cloudItems);
            HashMap<String, SyncRecordLookup.DownloadPatch> states = new HashMap<>(stableKeys.size());
            for (int i = 0; i < stableKeys.size(); i++) {
                states.put(stableKeys.get(i), lookup.resolveDownload(cloudItems.get(i)));
            }
            this.runOnUiIfAdded(() -> this.adapter.applyDownloadStates(states));
        }, "refresh-download-status").start();
    }

    private boolean usesCloudDownloadOverlay() {
        return isCloudTab() || isMyShareTab()
                || MediaListAdapter.TAB_FAMILY.equals(this.tabType);
    }

    private void runPhoneSyncVerifyForRows(List<MediaListAdapter.MediaRow> rows) {
        if (!isPhoneTab() || adapter == null || rows == null || rows.isEmpty()) {
            finishSwipeRefresh();
            return;
        }
        Context app = requireAppContext();
        if (app == null) {
            finishSwipeRefresh();
            return;
        }
        List<SyncVerifyScheduler.Target> targets = collectVerifyTargets(rows);
        if (targets.isEmpty()) {
            finishSwipeRefresh();
            return;
        }
        syncVerifyScheduler.verifyNow(app, targets, apiClient, result -> {
            if (!isAdded()) {
                return;
            }
            applyVerifyResult(result);
            finishSwipeRefresh();
        });
    }

    private void applyVerifyResult(SyncVerifyScheduler.VerifyResult result) {
        if (result == null || adapter == null) {
            return;
        }
        if (result.shareByPath != null && !result.shareByPath.isEmpty()) {
            Map<String, Integer> merged = new HashMap<>(shareByPath);
            merged.putAll(result.shareByPath);
            shareByPath = merged;
            adapter.applyShareState(shareByPath);
        }
        for (String key : result.invalidatedKeys) {
            patchLocalIndexAfterInvalidation(key);
            adapter.applyInvalidatedSync(key);
        }
        if (result.rebuiltRecords != null && !result.rebuiltRecords.isEmpty()) {
            for (SyncRecord record : result.rebuiltRecords) {
                if (record != null && record.localUri != null) {
                    pageSyncRecords.put(record.localUri, record);
                }
            }
            adapter.applySyncState(pageSyncRecords);
        }
    }

    private void patchLocalIndexAfterInvalidation(String stableKey) {
        if (stableKey == null) {
            return;
        }
        for (MediaListAdapter.MediaRow row : adapter.getRows()) {
            if (row == null || !stableKey.equals(row.stableKey())) {
                continue;
            }
            if (row.item == null || row.item.uri == null) {
                return;
            }
            SyncRecord record = pageSyncRecords.get(row.item.uri.toString());
            if (record != null) {
                record.remoteFileId = null;
                record.remotePath = null;
            }
            return;
        }
    }

    private List<SyncVerifyScheduler.Target> collectVerifyTargets(List<MediaListAdapter.MediaRow> rows) {
        List<SyncVerifyScheduler.Target> targets = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            MediaListAdapter.MediaRow row = rows.get(i);
            if (row == null || row.isCloudRow() || row.item == null || row.syncing) {
                continue;
            }
            if (row.mediaStoreOffset < 0 || row.item.uri == null) {
                continue;
            }
            SyncRecord record = pageSyncRecords.get(row.item.uri.toString());
            long remoteFileId = record != null && record.remoteFileId != null
                    ? record.remoteFileId : 0L;
            long capturedAt = row.item.takenAt > 0L ? row.item.takenAt : row.item.modifiedAt;
            String relativePath = row.item.relativePath;
            if (record != null && record.remotePath != null && !record.remotePath.isEmpty()) {
                relativePath = record.remotePath;
            }
            String fileHash = record != null && record.fileHash != null && !record.fileHash.isEmpty()
                    ? record.fileHash : null;
            targets.add(new SyncVerifyScheduler.Target(
                    i,
                    row.stableKey(),
                    row.mediaStoreOffset,
                    remoteFileId,
                    row.item.uri.toString(),
                    relativePath,
                    row.item.fileSize,
                    capturedAt,
                    row.item.fileName,
                    row.item.mediaType,
                    row.item.mediaId,
                    fileHash));
        }
        return targets;
    }

    private void finishSwipeRefresh() {
        if (swipeRefresh != null) {
            swipeRefresh.setRefreshing(false);
        }
    }

    @Override
    public void onPreviewClick(int position) {
        MediaListAdapter.MediaRow clicked = adapter.getRowAt(position);
        if (clicked == null) {
            return;
        }
        boolean isVideo = "VIDEO".equals(this.category);
        if (clicked.isCloudRow() || "family".equals(this.tabType) || "my_share".equals(this.tabType)) {
            if (isVideo) {
                this.openVideoPreview(clicked.cloudItem, clicked);
                return;
            }
            if ("AUDIO".equals(this.category)) {
                this.openAudioPreview(clicked.cloudItem, clicked);
                return;
            }
            List<MediaListAdapter.MediaRow> rows = this.adapter.getRows();
            ArrayList<String> cloudPaths = new ArrayList<String>();
            ArrayList<Long> ownerIds = new ArrayList<Long>();
            int previewIndex = 0;
            for (int i = 0; i < rows.size(); ++i) {
                MediaListAdapter.MediaRow row = rows.get(i);
                if (!row.isCloudRow() || isVideo) continue;
                if (i == position) {
                    previewIndex = cloudPaths.size();
                }
                cloudPaths.add(row.cloudItem.relativePath);
                ownerIds.add(row.cloudItem.ownerIdAsLong());
            }
            if (cloudPaths.isEmpty()) {
                Toast.makeText((Context)this.requireContext(), (CharSequence)"\u6ca1\u6709\u53ef\u9884\u89c8\u7684\u56fe\u7247", (int)0).show();
                return;
            }
            MediaPreviewStore.setCloudSession(cloudPaths, ownerIds, previewIndex);
            Intent intent = new Intent(this.requireContext(), ImagePreviewActivity.class);
            intent.putExtra("mode", "cloud");
            imagePreviewLauncher.launch(intent);
            return;
        }
        if (isVideo) {
            if (clicked.item != null) {
                this.openLocalVideo(clicked.item.uri);
            }
            return;
        }
        if ("AUDIO".equals(this.category)) {
            if (clicked.item != null) {
                this.openLocalAudio(clicked.item.uri);
            }
            return;
        }
        List<MediaListAdapter.MediaRow> rows = this.adapter.getRows();
        ArrayList<String> imageUris = new ArrayList<String>();
        int previewIndex = 0;
        for (int i = 0; i < rows.size(); ++i) {
            MediaListAdapter.MediaRow row = rows.get(i);
            if (row.isCloudRow()) continue;
            MediaScanner.MediaItem item = row.item;
            if (!"IMAGE".equals(item.mediaType)) continue;
            if (i == position) {
                previewIndex = imageUris.size();
            }
            imageUris.add(item.uri.toString());
        }
        if (imageUris.isEmpty()) {
            Toast.makeText((Context)this.requireContext(), (CharSequence)"\u5f53\u524d\u9879\u4e0d\u662f\u53ef\u9884\u89c8\u56fe\u7247", (int)0).show();
            return;
        }
        MediaPreviewStore.setLocalSession(imageUris, previewIndex);
        Intent intent = new Intent(this.requireContext(), ImagePreviewActivity.class);
        intent.putExtra("mode", "local");
        this.startActivity(intent);
    }

    private void openVideoPreview(AlbumPhotoItem item, MediaListAdapter.MediaRow row) {
        Intent intent = new Intent(this.requireContext(), VideoPreviewActivity.class);
        if (row != null && row.localThumbUri != null && !row.localThumbUri.isEmpty()) {
            intent.putExtra(VideoPreviewActivity.EXTRA_LOCAL_URI, row.localThumbUri);
        } else {
            intent.putExtra(VideoPreviewActivity.EXTRA_CLOUD_PATH, item.relativePath);
            Long ownerId = item.ownerIdAsLong();
            intent.putExtra(VideoPreviewActivity.EXTRA_OWNER_ID, ownerId != null ? ownerId : 0L);
        }
        videoPreviewLauncher.launch(intent);
    }

    private void removeGhostCloudPath(String relativePath) {
        forceReload();
    }

    private void openLocalVideo(Uri uri) {
        Intent intent = new Intent(this.requireContext(), VideoPreviewActivity.class);
        intent.putExtra("local_uri", uri.toString());
        this.startActivity(intent);
    }

    private void openAudioPreview(AlbumPhotoItem item, MediaListAdapter.MediaRow row) {
        Intent intent = new Intent(this.requireContext(), VideoPreviewActivity.class);
        if (row != null && row.localThumbUri != null && !row.localThumbUri.isEmpty()) {
            intent.putExtra(VideoPreviewActivity.EXTRA_LOCAL_URI, row.localThumbUri);
        } else {
            intent.putExtra(VideoPreviewActivity.EXTRA_CLOUD_PATH, item.relativePath);
            Long ownerId = item.ownerIdAsLong();
            intent.putExtra(VideoPreviewActivity.EXTRA_OWNER_ID, ownerId != null ? ownerId : 0L);
        }
        intent.putExtra(VideoPreviewActivity.EXTRA_AUDIO_MODE, true);
        this.startActivity(intent);
    }

    private void openLocalAudio(Uri uri) {
        Intent intent = new Intent("android.intent.action.VIEW");
        intent.setDataAndType(uri, "audio/*");
        intent.addFlags(1);
        try {
            this.startActivity(intent);
        }
        catch (Exception e) {
            Toast.makeText((Context)this.requireContext(), (CharSequence)"\u65e0\u6cd5\u64ad\u653e\u97f3\u9891", (int)0).show();
        }
    }

    @Override
    public void onSyncClick(MediaListAdapter.MediaRow row, final int position) {
        if (row.isCloudRow() || row.item == null) {
            return;
        }
        final MediaScanner.MediaItem item = row.item;
        final Context app = this.requireAppContext();
        this.adapter.setSyncing(position, true);
        new SyncEngine(app).syncSingle(item, new SyncEngine.Callback(){

            @Override
            public void onProgress(int current, int total, String fileName) {
            }

            @Override
            public void onUploadProgress(MediaScanner.MediaItem progressItem, int percent) {
                MediaTabFragment.this.runOnUiIfAdded(() -> MediaTabFragment.this.adapter.updateSyncProgress(position, percent));
            }

            @Override
            public void onComplete(int synced, int skipped, int failed, String lastError) {
                new Thread(() -> {
                    boolean syncedNow = failed == 0 && (synced > 0 || skipped > 0
                            || SyncStatusHelper.isSynced(app, item));
                    String message = syncedNow ? "\u540c\u6b65\u5b8c\u6210" : (lastError != null ? lastError : "\u540c\u6b65\u5931\u8d25");
                    MediaTabFragment.this.runOnUiIfAdded(() -> {
                        Toast.makeText((Context)MediaTabFragment.this.requireContext(), (CharSequence)message, (int)(syncedNow ? 0 : 1)).show();
                        MediaTabFragment.this.adapter.updateSynced(position, syncedNow);
                    });
                }).start();
            }

            @Override
            public void onError(String message) {
                MediaTabFragment.this.runOnUiIfAdded(() -> {
                    MediaTabFragment.this.adapter.setSyncing(position, false);
                    Toast.makeText((Context)MediaTabFragment.this.requireContext(), (CharSequence)message, (int)1).show();
                });
            }
        });
    }

    @Override
    public void onItemLongClick(int position) {
        MediaListAdapter.MediaRow row = adapter.getRowAt(position);
        if (row == null) {
            return;
        }
        if (row.isCloudRow() && "VIDEO".equals(this.category) && row.cloudItem != null
                && !isCloudTab() && !isPhoneTab() && !isMyShareTab()) {
            this.showCloudVideoDownloadOnly(row);
            return;
        }
        if (!isPhoneTab() && !isCloudTab() && !isMyShareTab()) {
            return;
        }
        if (!this.adapter.isSelectionMode()) {
            this.adapter.enterSelectionMode(position);
        } else if (!this.adapter.getSelectedPositions().contains(position)) {
            this.adapter.toggleSelection(position);
        }
        if (this.getActivity() instanceof MediaListActivity activity) {
            activity.updateSelectionTitle(this.adapter.getSelectedCount());
        }
    }

    private void showCloudVideoDownloadOnly(MediaListAdapter.MediaRow row) {
        new AlertDialog.Builder(this.requireContext())
                .setItems(new CharSequence[]{getString(R.string.cloud_action_download)},
                        (dialog, which) -> this.downloadCloudVideo(row.cloudItem))
                .show();
    }

    private void downloadCloudVideo(AlbumPhotoItem item) {
        if (item == null || item.relativePath == null) {
            return;
        }
        CloudDownloadRunner.startGallerySync((Fragment)this, this.apiClient, item.relativePath,
                item.ownerIdAsLong(), item.fileId, (name, saved) -> this.runOnUiIfAdded(() ->
                        this.applyAfterCloudDownload(item.relativePath, saved)));
    }

    private static String fileNameFromPath(String path) {
        if (path == null) {
            return "video.mp4";
        }
        int idx = path.lastIndexOf(47);
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    @Override
    public void onSelectionToggle(int position, boolean selected) {
        int count = this.adapter.getSelectedCount();
        if (count == 0 && this.adapter.isSelectionMode()) {
            clearSelection();
            return;
        }
        if (this.getActivity() instanceof MediaListActivity activity) {
            activity.updateSelectionTitle(count);
        }
    }

    public int getSelectedShareableCount() {
        return this.adapter.getSelectedShareablePaths().size();
    }

    public int getSelectedUnsharedShareCount() {
        return this.adapter.getSelectedUnsharedShareCount();
    }

    public int getSelectedSharedShareCount() {
        return this.adapter.getSelectedSharedShareCount();
    }

    public int getSelectedDeletableCount() {
        return this.adapter.getSelectedDeletableCount();
    }

    public int getSelectedDownloadCount() {
        return this.adapter.getSelectedDownloadTargets().size();
    }
}
