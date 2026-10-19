package com.yyba.cloud.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.yyba.cloud.R;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.backup.BackupForegroundService;
import com.yyba.cloud.backup.BackupItem;
import com.yyba.cloud.backup.BackupProgressBus;
import com.yyba.cloud.backup.BackupStatusResolver;
import com.yyba.cloud.backup.DeviceNameProvider;
import com.yyba.cloud.backup.FileEntry;
import com.yyba.cloud.backup.FileHashVerifyScheduler;
import com.yyba.cloud.backup.LocalFileBrowser;
import com.yyba.cloud.backup.StoragePermissionHelper;
import com.yyba.cloud.util.PrefsManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 手机备份：MediaStore 浏览，支持层级目录树 + 扁平分组两种模式 */
public class PhoneBackupActivity extends AppCompatActivity
        implements PhoneBackupAdapter.Listener {

    private enum State { BROWSE, PROGRESS, RESULT }
    private enum BrowseMode { HIERARCHY, FLAT }

    private MaterialToolbar toolbar;
    private TextView tvBreadcrumb;
    private RecyclerView rvFiles;
    private TextView tvEmpty;
    private View browseContainer;
    private View selectBar;
    private TextView tvSelectedCount;
    private MaterialButton btnSelectAll;
    private MaterialButton btnStartBackup;
    private View progressContainer;
    private ProgressBar progressBar;
    private TextView tvProgressText;
    private TextView tvProgressDetail;
    private MaterialButton btnCancelBackup;
    private View resultContainer;
    private TextView tvResultTitle;
    private TextView tvResultDetail;
    private MaterialButton btnBackToBrowse;

    private PhoneBackupAdapter adapter;
    private BackupStatusResolver statusResolver;
    private DeviceNameProvider deviceNameProvider;
    private FileHashVerifyScheduler verifyScheduler;
    private ApiClient apiClient;

    private BrowseMode browseMode = BrowseMode.HIERARCHY;
    /** 层级模式：当前所在目录路径（"" = 根）；扁平模式：当前查看的分组路径 */
    private String currentPath = "";
    /** 面包屑栈（层级模式用） */
    private final List<String> pathStack = new ArrayList<>();

    private final BackupProgressBus.Listener progressListener = new BackupProgressBus.Listener() {
        @Override
        public void onBackupEvent(BackupProgressBus.Event event, BackupProgressBus.Snapshot snapshot,
                                   int synced, int skipped, int failed, String message) {
            runOnUiThread(() -> {
                switch (event) {
                    case STARTED: showProgress(); break;
                    case PROGRESS: updateProgress(snapshot); break;
                    case COMPLETED: showResult(synced, skipped, failed, false, message); break;
                    case CANCELLED: showResult(synced, skipped, failed, true, null); break;
                    case ERROR: showResult(0, 0, 0, false, message); break;
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_backup);

        toolbar = findViewById(R.id.toolbar);
        tvBreadcrumb = findViewById(R.id.tvBreadcrumb);
        rvFiles = findViewById(R.id.rvFiles);
        tvEmpty = findViewById(R.id.tvEmpty);
        browseContainer = findViewById(R.id.browseContainer);
        selectBar = findViewById(R.id.selectBar);
        tvSelectedCount = findViewById(R.id.tvSelectedCount);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnStartBackup = findViewById(R.id.btnStartBackup);
        progressContainer = findViewById(R.id.progressContainer);
        progressBar = findViewById(R.id.progressBar);
        tvProgressText = findViewById(R.id.tvProgressText);
        tvProgressDetail = findViewById(R.id.tvProgressDetail);
        btnCancelBackup = findViewById(R.id.btnCancelBackup);
        resultContainer = findViewById(R.id.resultContainer);
        tvResultTitle = findViewById(R.id.tvResultTitle);
        tvResultDetail = findViewById(R.id.tvResultDetail);
        btnBackToBrowse = findViewById(R.id.btnBackToBrowse);

        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        deviceNameProvider = new DeviceNameProvider(this);
        statusResolver = new BackupStatusResolver(this);
        verifyScheduler = new FileHashVerifyScheduler();
        apiClient = new ApiClient(new PrefsManager(this));

        adapter = new PhoneBackupAdapter(this);
        rvFiles.setLayoutManager(new LinearLayoutManager(this));
        rvFiles.setAdapter(adapter);

        btnSelectAll.setOnClickListener(v -> {
            adapter.selectAllVisible();
            updateSelectBar();
        });
        btnStartBackup.setOnClickListener(v -> startBackup());
        btnCancelBackup.setOnClickListener(v -> {
            Intent intent = new Intent(this, BackupForegroundService.class);
            intent.setAction(BackupForegroundService.ACTION_BACKUP_CANCEL);
            startService(intent);
        });
        btnBackToBrowse.setOnClickListener(v -> showBrowse());

        if (savedInstanceState != null) {
            browseMode = BrowseMode.values()[savedInstanceState.getInt("browseMode", 0)];
            currentPath = savedInstanceState.getString("currentPath", "");
        }

        if (!StoragePermissionHelper.hasPermission(this)) {
            showPermissionDialog();
        } else {
            loadCurrentView();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("browseMode", browseMode.ordinal());
        outState.putString("currentPath", currentPath);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.phone_backup_menu, menu);
        updateMenuIcon(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_toggle_view) {
            toggleBrowseMode();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateMenuIcon(Menu menu) {
        MenuItem toggle = menu.findItem(R.id.action_toggle_view);
        if (toggle == null) return;
        if (browseMode == BrowseMode.HIERARCHY) {
            toggle.setIcon(android.R.drawable.ic_menu_sort_by_size);
            toggle.setTitle(R.string.phone_backup_flat_view);
        } else {
            toggle.setIcon(android.R.drawable.ic_menu_view);
            toggle.setTitle(R.string.phone_backup_hierarchy_view);
        }
    }

    private void toggleBrowseMode() {
        browseMode = (browseMode == BrowseMode.HIERARCHY)
                ? BrowseMode.FLAT : BrowseMode.HIERARCHY;
        currentPath = "";
        pathStack.clear();
        invalidateOptionsMenu();
        loadCurrentView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BackupProgressBus.addListener(progressListener);
        if (BackupProgressBus.isRunning()) {
            showProgress();
            BackupProgressBus.Snapshot snap = BackupProgressBus.current();
            if (snap.running) updateProgress(snap);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        BackupProgressBus.removeListener(progressListener);
    }

    @Override
    public void onBackPressed() {
        if (adapter.isSelectionMode()) {
            adapter.exitSelectionMode();
            updateSelectBar();
            return;
        }
        // 层级模式：有子目录返回则回退
        if (browseMode == BrowseMode.HIERARCHY) {
            if (!pathStack.isEmpty()) {
                pathStack.remove(pathStack.size() - 1);
                currentPath = pathStack.isEmpty() ? "" : pathStack.get(pathStack.size() - 1);
                loadCurrentView();
                return;
            }
        }
        // 扁平模式：在分组列表 → 直接退出；在文件列表 → 回到分组列表
        if (browseMode == BrowseMode.FLAT && !currentPath.isEmpty()) {
            currentPath = "";
            loadCurrentView();
            return;
        }
        super.onBackPressed();
    }

    // ---- PhoneBackupAdapter.Listener ----

    @Override
    public void onItemClick(FileEntry entry, int position) {
        if (adapter.isSelectionMode()) {
            if (!entry.isDirectory && !entry.isBackedUp()) {
                adapter.toggleSelection(entry.getSelectionKey());
                updateSelectBar();
            }
            return;
        }
        if (entry.isDirectory) {
            enterDirectory(entry);
        } else if (PhoneBackupAdapter.isImage(entry.name)) {
            openImagePreview(entry);
        } else {
            // 视频、音频、文档：系统 ACTION_VIEW 预览
            openWithSystemViewer(entry);
        }
    }

    @Override
    public void onItemLongClick(FileEntry entry, int position) {
        if (!adapter.isSelectionMode() && !entry.isDirectory && !entry.isBackedUp()) {
            adapter.enterSelectionMode();
            adapter.toggleSelection(entry.getSelectionKey());
            updateSelectBar();
        }
    }

    // ---- 目录导航 ----

    private void enterDirectory(FileEntry entry) {
        if (browseMode == BrowseMode.HIERARCHY) {
            pathStack.add(entry.dirFullPath);
            currentPath = entry.dirFullPath;
        } else {
            // 扁平模式：从分组列表 → 文件列表
            currentPath = entry.dirFullPath;
        }
        loadCurrentView();
    }

    private void loadCurrentView() {
        updateBreadcrumb();
        if (browseMode == BrowseMode.HIERARCHY) {
            if (pathStack.isEmpty()) {
                loadHierarchyRoot();
            } else {
                loadHierarchyDir(currentPath);
            }
        } else {
            if (currentPath.isEmpty()) {
                loadFlatGroups();
            } else {
                loadFlatFiles(currentPath);
            }
        }
    }

    private void updateBreadcrumb() {
        if (browseMode == BrowseMode.HIERARCHY) {
            if (pathStack.isEmpty()) {
                tvBreadcrumb.setText(R.string.phone_backup_root);
            } else {
                tvBreadcrumb.setText(currentPath.replace("/", " / "));
            }
        } else {
            if (currentPath.isEmpty()) {
                tvBreadcrumb.setText(R.string.phone_backup_root);
            } else {
                tvBreadcrumb.setText(currentPath.replace("/", " / "));
            }
        }
    }

    // ---- 层级模式 ----

    private void loadHierarchyRoot() {
        new Thread(() -> {
            List<LocalFileBrowser.DirectoryNode> roots = LocalFileBrowser.listDirectoryTree(this);
            List<FileEntry> entries = new ArrayList<>();
            for (LocalFileBrowser.DirectoryNode node : roots) {
                entries.add(FileEntry.directory(node));
            }
            runOnUiThread(() -> {
                adapter.setItems(entries);
                tvEmpty.setText(R.string.phone_backup_no_groups);
                tvEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void loadHierarchyDir(String dirPath) {
        new Thread(() -> {
            LocalFileBrowser.DirectoryChildren children =
                    LocalFileBrowser.listDirectoryChildren(this, dirPath);
            List<FileEntry> entries = new ArrayList<>();
            // 先放子目录
            for (LocalFileBrowser.DirectoryNode sub : children.directories) {
                entries.add(FileEntry.directory(sub));
            }
            // 再放直接文件
            for (LocalFileBrowser.MediaFile mf : children.files) {
                entries.add(FileEntry.file(mf));
            }
            // Tier 0 状态填充
            statusResolver.fillStatus(entries);

            runOnUiThread(() -> {
                adapter.setItems(entries);
                tvEmpty.setText(R.string.phone_backup_no_files);
                tvEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            });
            // Tier 2 兜底
            runTier2IfNeeded(entries);
        }).start();
    }

    // ---- 扁平模式 ----

    private void loadFlatGroups() {
        new Thread(() -> {
            List<LocalFileBrowser.FileGroup> groups = LocalFileBrowser.listGroups(this);
            List<FileEntry> entries = new ArrayList<>();
            for (LocalFileBrowser.FileGroup g : groups) {
                entries.add(FileEntry.group(g));
            }
            runOnUiThread(() -> {
                adapter.setItems(entries);
                tvEmpty.setText(R.string.phone_backup_no_groups);
                tvEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void loadFlatFiles(String groupPath) {
        new Thread(() -> {
            List<LocalFileBrowser.MediaFile> files = LocalFileBrowser.listInGroup(this, groupPath);
            List<FileEntry> entries = new ArrayList<>();
            for (LocalFileBrowser.MediaFile mf : files) {
                entries.add(FileEntry.file(mf));
            }
            statusResolver.fillStatus(entries);
            runOnUiThread(() -> {
                adapter.setItems(entries);
                tvEmpty.setText(R.string.phone_backup_no_files);
                tvEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            });
            runTier2IfNeeded(entries);
        }).start();
    }

    // ---- Tier 2 网络校验 ----

    private void runTier2IfNeeded(List<FileEntry> entries) {
        List<FileHashVerifyScheduler.Target> targets = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            FileEntry e = entries.get(i);
            if (e.isDirectory) continue;
            if (e.backupStatus != FileEntry.BackupStatus.UNCHECKED) continue;
            String localUri = e.mediaUri != null ? e.mediaUri.toString() : null;
            if (localUri == null) continue;
            targets.add(new FileHashVerifyScheduler.Target(i, localUri, e.name, e.size));
        }
        if (targets.isEmpty()) return;

        verifyScheduler.verifyNow(this, targets, apiClient, result -> runOnUiThread(() -> {
            List<FileEntry> current = adapter.getCurrentEntries();
            if (current == null) return;
            boolean changed = false;
            for (Map.Entry<Integer, FileEntry.BackupStatus> me : result.statusByPosition.entrySet()) {
                int pos = me.getKey();
                if (pos >= 0 && pos < current.size()) {
                    FileEntry entry = current.get(pos);
                    if (entry.backupStatus != me.getValue()) {
                        entry.backupStatus = me.getValue();
                        changed = true;
                    }
                }
            }
            for (Map.Entry<Integer, String> me : result.sourceByPosition.entrySet()) {
                int pos = me.getKey();
                if (pos >= 0 && pos < current.size()) {
                    current.get(pos).backupSource = me.getValue();
                    changed = true;
                }
            }
            if (changed) adapter.notifyDataSetChanged();
        }));
    }

    // ---- 图片预览 ----

    private void openImagePreview(FileEntry clicked) {
        List<String> imageUris = adapter.getImageUris();
        if (imageUris.isEmpty()) return;
        String clickedUri = clicked.mediaUri != null ? clicked.mediaUri.toString() : null;
        int index = clickedUri != null ? imageUris.indexOf(clickedUri) : -1;
        if (index < 0) {
            if (clickedUri != null) imageUris.add(0, clickedUri);
            index = 0;
        }
        MediaPreviewStore.setLocalSession(imageUris, index);
        Intent intent = new Intent(this, ImagePreviewActivity.class);
        intent.putExtra(ImagePreviewActivity.EXTRA_MODE, ImagePreviewActivity.MODE_LOCAL);
        startActivity(intent);
    }

    // ---- 系统预览（视频 / 音频 / 文档） ----

    private void openWithSystemViewer(FileEntry entry) {
        if (entry.mediaUri == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        String mimeType = PhoneBackupAdapter.documentMimeType(entry.name);
        intent.setDataAndType(entry.mediaUri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "未找到可打开此文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- 选择栏 ----

    private void updateSelectBar() {
        if (adapter.isSelectionMode()) {
            selectBar.setVisibility(View.VISIBLE);
            int count = adapter.getSelectedCount();
            tvSelectedCount.setText(getString(R.string.phone_backup_selected_count, count));
            btnStartBackup.setEnabled(count > 0);
        } else {
            selectBar.setVisibility(View.GONE);
        }
    }

    // ---- 启动备份 ----

    private void startBackup() {
        Set<String> selectedKeys = adapter.getSelectedKeys();
        if (selectedKeys.isEmpty()) {
            Toast.makeText(this, R.string.phone_backup_no_files_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        List<FileEntry> entries = adapter.getCurrentEntries();
        Set<String> backedUp = new java.util.HashSet<>();
        for (FileEntry e : entries) {
            if (e.backupStatus == FileEntry.BackupStatus.BACKED_UP) {
                backedUp.add(e.getSelectionKey());
            }
        }
        selectedKeys.removeAll(backedUp);
        if (selectedKeys.isEmpty()) {
            Toast.makeText(this, "所选文件均已备份", Toast.LENGTH_SHORT).show();
            return;
        }

        String deviceName = deviceNameProvider.getDeviceName();
        List<BackupItem> items = new ArrayList<>();
        for (FileEntry e : entries) {
            if (e.isDirectory || e.isBackedUp()) continue;
            if (!selectedKeys.contains(e.getSelectionKey())) continue;
            if (e.mediaUri == null) continue;

            String relPath = buildBackupRelativePath(deviceName, e);
            items.add(new BackupItem(e.mediaUri, e.mediaId, e.name, e.size,
                    e.mediaType, e.modifiedAt, e.takenAt, relPath));
        }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.phone_backup_no_files_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        adapter.exitSelectionMode();
        updateSelectBar();

        android.util.Log.d("PhoneBackup", "startBackup: selected=" + selectedKeys.size()
                + " backedUp=" + backedUp.size() + " items=" + items.size());
        BackupForegroundService.setPendingItems(items);
        Intent intent = new Intent(this, BackupForegroundService.class);
        intent.setAction(BackupForegroundService.ACTION_BACKUP_SYNC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        showProgress();
    }

    private String buildBackupRelativePath(String deviceName, FileEntry e) {
        // 按 mediaType 归入云端标准前缀，phoneBackup/ 作为子目录，
        // 这样云端相册 Tab（按路径前缀过滤）和网盘 Tab 都能看到。
        String typePrefix;
        switch (e.mediaType != null ? e.mediaType : "") {
            case "VIDEO": typePrefix = "videos/"; break;
            case "AUDIO": typePrefix = "audio/"; break;
            case "DOCUMENT": typePrefix = "uploads/documents/"; break;
            default: typePrefix = "photos/"; break;
        }
        StringBuilder sb = new StringBuilder(typePrefix);
        sb.append("phoneBackup/").append(deviceName).append("/");
        if (e.relativePath != null && !e.relativePath.isEmpty() && !"/".equals(e.relativePath)) {
            String rp = e.relativePath.endsWith("/")
                    ? e.relativePath.substring(0, e.relativePath.length() - 1)
                    : e.relativePath;
            sb.append(rp).append("/");
        }
        sb.append(e.name);
        return sb.toString();
    }

    // ---- 状态切换 ----

    private void showBrowse() {
        browseContainer.setVisibility(View.VISIBLE);
        selectBar.setVisibility(adapter.isSelectionMode() ? View.VISIBLE : View.GONE);
        progressContainer.setVisibility(View.GONE);
        resultContainer.setVisibility(View.GONE);
        loadCurrentView();
    }

    private void showProgress() {
        browseContainer.setVisibility(View.GONE);
        selectBar.setVisibility(View.GONE);
        progressContainer.setVisibility(View.VISIBLE);
        resultContainer.setVisibility(View.GONE);
    }

    private void updateProgress(BackupProgressBus.Snapshot snap) {
        int percent = snap.percent > 0 ? snap.percent : 0;
        progressBar.setProgress(percent);
        String text = snap.total > 0
                ? getString(R.string.sync_progress_format, snap.current, snap.total)
                : getString(R.string.phone_backup_running);
        tvProgressText.setText(text);
        if (!snap.fileName.isEmpty()) {
            tvProgressDetail.setText(snap.fileName + " " + percent + "%");
        }
    }

    private void showResult(int synced, int skipped, int failed, boolean cancelled, String message) {
        browseContainer.setVisibility(View.GONE);
        selectBar.setVisibility(View.GONE);
        progressContainer.setVisibility(View.GONE);
        resultContainer.setVisibility(View.VISIBLE);

        if (cancelled) {
            tvResultTitle.setText(R.string.phone_backup_cancelled);
        } else if (failed > 0) {
            tvResultTitle.setText(R.string.phone_backup_has_failures);
        } else {
            tvResultTitle.setText(R.string.phone_backup_done);
        }
        tvResultDetail.setText(getString(R.string.phone_backup_result_detail, synced, skipped, failed));
    }

    // ---- 权限 ----

    private void showPermissionDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.phone_backup_no_permission_title)
                .setMessage(R.string.phone_backup_no_permission_msg)
                .setPositiveButton(R.string.phone_backup_go_settings,
                        (d, w) -> StoragePermissionHelper.requestPermission(this))
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (StoragePermissionHelper.hasPermission(this)) {
            loadCurrentView();
        } else {
            showPermissionDialog();
        }
    }
}
