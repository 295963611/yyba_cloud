package com.yyba.cloud.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.yyba.cloud.R;
import com.yyba.cloud.api.AlbumPhotoItem;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.sync.MediaScanner;
import com.yyba.cloud.util.MediaGlideLoader;
import com.yyba.cloud.util.SyncRecordLookup;
import com.yyba.cloud.util.SyncStatusHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MediaListAdapter extends PagingDataAdapter<MediaListAdapter.MediaRow, MediaListAdapter.ViewHolder> {

    private static final Object PAYLOAD_THUMB = "thumb";
    private static final Object PAYLOAD_OVERLAY = "overlay";

    public static final int SOURCE_LOCAL = 0;
    public static final int SOURCE_CLOUD = 1;

    public static final String TAB_PHONE = "phone";
    public static final String TAB_CLOUD = "cloud";
    public static final String TAB_FAMILY = "family";
    public static final String TAB_MY_SHARE = "my_share";

    public interface Listener {
        void onSyncClick(MediaRow row, int position);
        void onPreviewClick(int position);
        void onItemLongClick(int position);
        void onSelectionToggle(int position, boolean selected);
    }

    public static class MediaRow {
        public final int source;
        public final MediaScanner.MediaItem item;
        public final AlbumPhotoItem cloudItem;
        public boolean synced;
        public boolean syncing;
        public int syncProgress = -1;
        public boolean shared;
        public boolean downloaded;
        public String localThumbUri;
        public String remotePath;
        public int mediaStoreOffset = -1;

        private MediaRow(int source, MediaScanner.MediaItem item, AlbumPhotoItem cloudItem) {
            this.source = source;
            this.item = item;
            this.cloudItem = cloudItem;
        }

        public static MediaRow local(MediaScanner.MediaItem item, boolean synced, boolean shared,
                                     String remotePath, int mediaStoreOffset) {
            MediaRow row = new MediaRow(SOURCE_LOCAL, item, null);
            row.synced = synced;
            row.shared = shared;
            row.remotePath = remotePath;
            row.mediaStoreOffset = mediaStoreOffset;
            return row;
        }

        public static MediaRow cloudIndex(AlbumPhotoItem cloudItem, boolean downloaded) {
            return cloudIndex(cloudItem, downloaded, null);
        }

        public static MediaRow cloudIndex(AlbumPhotoItem cloudItem, boolean downloaded,
                                          @Nullable String localThumbUri) {
            MediaRow row = new MediaRow(SOURCE_CLOUD, null, cloudItem);
            row.downloaded = downloaded;
            row.localThumbUri = localThumbUri;
            row.shared = cloudItem != null && cloudItem.share != null && cloudItem.share == 1;
            return row;
        }

        public static MediaRow cloudFromPatch(AlbumPhotoItem cloudItem,
                                              SyncRecordLookup.DownloadPatch patch) {
            if (patch == null) {
                return cloudIndex(cloudItem, false);
            }
            return cloudIndex(cloudItem, patch.downloaded, patch.localThumbUri);
        }

        public static MediaRow sharedCloud(AlbumPhotoItem cloudItem) {
            return sharedCloud(cloudItem, false, null);
        }

        public static MediaRow sharedCloud(AlbumPhotoItem cloudItem, boolean downloaded,
                                           @Nullable String localThumbUri) {
            MediaRow row = cloudIndex(cloudItem, downloaded, localThumbUri);
            row.shared = true;
            return row;
        }

        public static MediaRow sharedFromPatch(AlbumPhotoItem cloudItem,
                                               SyncRecordLookup.DownloadPatch patch) {
            if (patch == null) {
                return sharedCloud(cloudItem);
            }
            return sharedCloud(cloudItem, patch.downloaded, patch.localThumbUri);
        }

        public boolean isCloudRow() {
            return source == SOURCE_CLOUD;
        }

        @Deprecated
        public boolean isCloudOnly() {
            return isCloudRow();
        }

        public String stableKey() {
            if (isCloudRow() && cloudItem != null && cloudItem.relativePath != null) {
                Long ownerId = cloudItem.ownerIdAsLong();
                return "cloud:" + cloudItem.relativePath + "#" + (ownerId != null ? ownerId : 0L);
            }
            if (item != null && item.uri != null) {
                return "local:" + item.uri;
            }
            return "row:" + System.identityHashCode(this);
        }

        public String shareablePath() {
            if (isCloudRow() && cloudItem != null) {
                return cloudItem.relativePath;
            }
            if (synced) {
                if (remotePath != null && !remotePath.isEmpty()) {
                    return remotePath;
                }
                if (item != null) {
                    return item.relativePath;
                }
            }
            return null;
        }
    }

    private static final DiffUtil.ItemCallback<MediaRow> DIFF_CALLBACK = new DiffUtil.ItemCallback<MediaRow>() {
        @Override
        public boolean areItemsTheSame(@NonNull MediaRow oldItem, @NonNull MediaRow newItem) {
            return oldItem.stableKey().equals(newItem.stableKey());
        }

        @Override
        public boolean areContentsTheSame(@NonNull MediaRow oldItem, @NonNull MediaRow newItem) {
            return oldItem.synced == newItem.synced
                    && oldItem.shared == newItem.shared
                    && oldItem.downloaded == newItem.downloaded
                    && Objects.equals(oldItem.localThumbUri, newItem.localThumbUri)
                    && oldItem.syncing == newItem.syncing
                    && oldItem.syncProgress == newItem.syncProgress;
        }
    };

    private final Listener listener;
    private final ApiClient apiClient;
    private final String tabType;
    private boolean selectionMode;
    private final Set<String> selectedKeys = new HashSet<>();
    private final ConcurrentHashMap<String, RowOverlayState> overlayByKey = new ConcurrentHashMap<>();

    public MediaListAdapter(Listener listener, ApiClient apiClient, String tabType) {
        super(DIFF_CALLBACK);
        this.listener = listener;
        this.apiClient = apiClient;
        this.tabType = tabType;
    }

    public void clearOverlayState() {
        overlayByKey.clear();
        selectedKeys.clear();
    }

    public static void setScrollPaused(Context context, boolean paused) {
        MediaGlideLoader.setPaused(context, paused);
    }

    public void refreshVisibleThumbnails(GridLayoutManager layoutManager) {
        if (layoutManager == null) {
            return;
        }
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return;
        }
        for (int i = first; i <= last; i++) {
            notifyItemChanged(i, PAYLOAD_THUMB);
        }
    }

    private MediaRow mergeOverlay(MediaRow row) {
        if (row == null) {
            return null;
        }
        RowOverlayState overlay = overlayByKey.get(row.stableKey());
        if (overlay != null) {
            overlay.applyTo(row);
        }
        return row;
    }

    private void putOverlay(String key, RowOverlayState state) {
        if (key == null || state == null) {
            return;
        }
        overlayByKey.put(key, state);
    }

    private void notifyKeyChanged(String key) {
        if (key == null) {
            return;
        }
        List<MediaRow> items = snapshot().getItems();
        for (int i = 0; i < items.size(); i++) {
            MediaRow row = items.get(i);
            if (row != null && key.equals(row.stableKey())) {
                notifyItemChanged(i, PAYLOAD_OVERLAY);
                return;
            }
        }
    }

    public List<MediaRow> getRows() {
        List<MediaRow> rows = new ArrayList<>();
        for (MediaRow row : snapshot().getItems()) {
            if (row != null) {
                rows.add(mergeOverlay(copyRow(row)));
            }
        }
        return rows;
    }

    private MediaRow copyRow(MediaRow source) {
        if (source.isCloudRow()) {
            MediaRow row = MediaRow.cloudIndex(source.cloudItem, source.downloaded, source.localThumbUri);
            row.synced = source.synced;
            row.syncing = source.syncing;
            row.syncProgress = source.syncProgress;
            row.shared = source.shared;
            row.remotePath = source.remotePath;
            row.mediaStoreOffset = source.mediaStoreOffset;
            return row;
        }
        MediaRow row = MediaRow.local(source.item, source.synced, source.shared,
                source.remotePath, source.mediaStoreOffset);
        row.syncing = source.syncing;
        row.syncProgress = source.syncProgress;
        return row;
    }

    public MediaRow getRowAt(int position) {
        MediaRow row = getItem(position);
        return mergeOverlay(row != null ? copyRow(row) : null);
    }

    private boolean supportsDownloadOverlay() {
        return TAB_CLOUD.equals(tabType) || TAB_FAMILY.equals(tabType) || TAB_MY_SHARE.equals(tabType);
    }

    public void setSelectionMode(boolean selectionMode) {
        this.selectionMode = selectionMode;
        if (!selectionMode) {
            selectedKeys.clear();
        }
        notifyDataSetChanged();
    }

    public void enterSelectionMode(int position) {
        MediaRow row = getRowAt(position);
        if (row == null) {
            return;
        }
        selectionMode = true;
        selectedKeys.add(row.stableKey());
        notifyDataSetChanged();
        listener.onSelectionToggle(position, true);
    }

    public void toggleSelection(int position) {
        MediaRow row = getRowAt(position);
        if (row == null) {
            return;
        }
        String key = row.stableKey();
        boolean selected;
        if (selectedKeys.contains(key)) {
            selectedKeys.remove(key);
            selected = false;
        } else {
            selectedKeys.add(key);
            selected = true;
        }
        notifyItemChanged(position);
        listener.onSelectionToggle(position, selected);
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public Set<Integer> getSelectedPositions() {
        Set<Integer> positions = new HashSet<>();
        List<MediaRow> items = snapshot().getItems();
        for (int i = 0; i < items.size(); i++) {
            MediaRow row = items.get(i);
            if (row != null && selectedKeys.contains(row.stableKey())) {
                positions.add(i);
            }
        }
        return positions;
    }

    public int getSelectedCount() {
        return selectedKeys.size();
    }

    private List<MediaRow> getSelectedRows() {
        List<MediaRow> selected = new ArrayList<>();
        for (MediaRow row : snapshot().getItems()) {
            if (row != null && selectedKeys.contains(row.stableKey())) {
                selected.add(mergeOverlay(copyRow(row)));
            }
        }
        return selected;
    }

    public List<String> getSelectedShareablePaths() {
        List<String> paths = new ArrayList<>();
        for (MediaRow row : getSelectedRows()) {
            String path = row.shareablePath();
            if (path != null) {
                paths.add(path);
            }
        }
        return paths;
    }

    public List<String> getSelectedUnsharedPaths() {
        List<String> paths = new ArrayList<>();
        for (MediaRow row : getSelectedRows()) {
            String path = row.shareablePath();
            if (path != null && !row.shared) {
                paths.add(path);
            }
        }
        return paths;
    }

    public List<String> getSelectedSharedPaths() {
        List<String> paths = new ArrayList<>();
        for (MediaRow row : getSelectedRows()) {
            String path = row.shareablePath();
            if (path != null && row.shared) {
                paths.add(path);
            }
        }
        return paths;
    }

    public int getSelectedUnsharedShareCount() {
        return getSelectedUnsharedPaths().size();
    }

    public int getSelectedSharedShareCount() {
        return getSelectedSharedPaths().size();
    }

    public List<MediaRow> getSelectedLocalRows() {
        List<MediaRow> selected = new ArrayList<>();
        if (!TAB_PHONE.equals(tabType)) {
            return selected;
        }
        for (MediaRow row : getSelectedRows()) {
            if (!row.isCloudRow() && row.item != null && row.item.uri != null) {
                selected.add(row);
            }
        }
        return selected;
    }

    public List<String> getSelectedCloudDeletePaths() {
        List<String> paths = new ArrayList<>();
        if (!TAB_CLOUD.equals(tabType)) {
            return paths;
        }
        for (MediaRow row : getSelectedRows()) {
            if (row.isCloudRow() && row.cloudItem != null && row.cloudItem.relativePath != null) {
                paths.add(row.cloudItem.relativePath);
            }
        }
        return paths;
    }

    public int getSelectedDeletableCount() {
        if (TAB_PHONE.equals(tabType)) {
            return getSelectedLocalRows().size();
        }
        if (TAB_CLOUD.equals(tabType)) {
            return getSelectedCloudDeletePaths().size();
        }
        return 0;
    }

    public static final class DownloadTarget {
        public final String relativePath;
        public final Long ownerId;
        public final Long fileId;

        public DownloadTarget(String relativePath, Long ownerId, Long fileId) {
            this.relativePath = relativePath;
            this.ownerId = ownerId;
            this.fileId = fileId;
        }
    }

    public List<DownloadTarget> getSelectedDownloadTargets() {
        List<DownloadTarget> targets = new ArrayList<>();
        for (MediaRow row : getSelectedRows()) {
            if (!TAB_CLOUD.equals(tabType) || !row.isCloudRow() || row.cloudItem == null) {
                continue;
            }
            String path = row.cloudItem.relativePath;
            if (path != null) {
                targets.add(new DownloadTarget(path, row.cloudItem.ownerIdAsLong(), row.cloudItem.fileId));
            }
        }
        return targets;
    }

    public void setSyncing(int position, boolean syncing) {
        MediaRow row = getRowAt(position);
        if (row == null) {
            return;
        }
        RowOverlayState state = RowOverlayState.fromRow(row);
        state.syncing = syncing;
        state.syncProgress = syncing ? 0 : -1;
        putOverlay(row.stableKey(), state);
        notifyItemChanged(position, PAYLOAD_OVERLAY);
    }

    public void updateSyncProgress(int position, int progress) {
        MediaRow row = getRowAt(position);
        if (row == null || !row.syncing) {
            return;
        }
        RowOverlayState state = RowOverlayState.fromRow(row);
        state.syncProgress = Math.max(0, Math.min(100, progress));
        putOverlay(row.stableKey(), state);
        notifyItemChanged(position, PAYLOAD_OVERLAY);
    }

    public void updateSynced(int position, boolean synced) {
        MediaRow row = getRowAt(position);
        if (row == null) {
            return;
        }
        RowOverlayState state = RowOverlayState.fromRow(row);
        state.synced = synced;
        state.syncing = false;
        state.syncProgress = -1;
        putOverlay(row.stableKey(), state);
        notifyItemChanged(position, PAYLOAD_OVERLAY);
    }

    public void applyShareState(Map<String, Integer> shareByPath) {
        if (shareByPath == null) {
            return;
        }
        List<MediaRow> items = snapshot().getItems();
        for (int i = 0; i < items.size(); i++) {
            MediaRow row = items.get(i);
            if (row == null) {
                continue;
            }
            String path = mergeOverlay(copyRow(row)).shareablePath();
            if (path == null) {
                continue;
            }
            Integer share = shareByPath.get(path);
            RowOverlayState state = RowOverlayState.fromRow(mergeOverlay(copyRow(row)));
            state.shared = share != null && share == 1;
            putOverlay(row.stableKey(), state);
            if (row.cloudItem != null) {
                row.cloudItem.share = state.shared ? 1 : 0;
            }
            notifyItemChanged(i, PAYLOAD_OVERLAY);
        }
    }

    public void applySyncState(Map<String, SyncRecord> localIndex) {
        if (!TAB_PHONE.equals(tabType)) {
            return;
        }
        List<MediaRow> items = snapshot().getItems();
        for (int i = 0; i < items.size(); i++) {
            MediaRow row = items.get(i);
            if (row == null || row.isCloudRow() || row.item == null) {
                continue;
            }
            MediaRow merged = mergeOverlay(copyRow(row));
            if (merged.syncing) {
                continue;
            }
            SyncRecord record = localIndex != null && row.item.uri != null
                    ? localIndex.get(row.item.uri.toString()) : null;
            boolean synced = SyncStatusHelper.isSyncedByRecord(record);
            if (merged.synced == synced && (record == null || record.remotePath == null
                    || record.remotePath.equals(merged.remotePath))) {
                continue;
            }
            RowOverlayState state = RowOverlayState.fromRow(merged);
            state.synced = synced;
            if (record != null) {
                state.remotePath = record.remotePath;
            }
            putOverlay(row.stableKey(), state);
            notifyItemChanged(i, PAYLOAD_OVERLAY);
        }
    }

    @Deprecated
    public void applySyncState(Context context, Map<String, SyncRecord> localIndex,
                               Set<String> serverPaths) {
        applySyncState(localIndex);
    }

    public void applyInvalidatedSync(String stableKey) {
        if (!TAB_PHONE.equals(tabType) || stableKey == null) {
            return;
        }
        RowOverlayState state = overlayByKey.get(stableKey);
        if (state == null) {
            state = new RowOverlayState();
        }
        state.synced = false;
        state.remotePath = null;
        state.shared = false;
        putOverlay(stableKey, state);
        notifyKeyChanged(stableKey);
    }

    public void applyInvalidatedSync(int position) {
        MediaRow row = getRowAt(position);
        if (row != null) {
            applyInvalidatedSync(row.stableKey());
        }
    }

    public void applyDownloadStates(Map<String, SyncRecordLookup.DownloadPatch> patches) {
        if (!supportsDownloadOverlay() || patches == null || patches.isEmpty()) {
            return;
        }
        List<MediaRow> items = snapshot().getItems();
        for (int i = 0; i < items.size(); i++) {
            MediaRow row = items.get(i);
            if (row == null || !row.isCloudRow() || row.cloudItem == null) {
                continue;
            }
            SyncRecordLookup.DownloadPatch patch = patches.get(row.stableKey());
            if (patch == null) {
                continue;
            }
            MediaRow merged = mergeOverlay(copyRow(row));
            if (merged.downloaded == patch.downloaded
                    && Objects.equals(merged.localThumbUri, patch.localThumbUri)) {
                continue;
            }
            RowOverlayState state = RowOverlayState.fromRow(merged);
            state.downloaded = patch.downloaded;
            state.localThumbUri = patch.localThumbUri;
            putOverlay(row.stableKey(), state);
            notifyItemChanged(i, PAYLOAD_OVERLAY);
            notifyItemChanged(i, PAYLOAD_THUMB);
        }
    }

    /** @deprecated 请在后台线程完成 lookup 后调用 {@link #applyDownloadStates(Map)} */
    @Deprecated
    public void applyDownloadState(Context context, SyncRecordLookup.CloudPageLookup lookup) {
        if (context == null || lookup == null || !TAB_CLOUD.equals(tabType)) {
            return;
        }
        List<MediaRow> items = snapshot().getItems();
        for (int i = 0; i < items.size(); i++) {
            MediaRow row = items.get(i);
            if (row == null || !row.isCloudRow() || row.cloudItem == null) {
                continue;
            }
            MediaRow merged = mergeOverlay(copyRow(row));
            boolean downloaded = lookup.isDownloaded(row.cloudItem);
            if (merged.downloaded == downloaded) {
                continue;
            }
            RowOverlayState state = RowOverlayState.fromRow(merged);
            state.downloaded = downloaded;
            putOverlay(row.stableKey(), state);
            notifyItemChanged(i, PAYLOAD_OVERLAY);
        }
    }

    public void applyDownloadState(Context context, Map<String, SyncRecord> localIndex) {
        if (context == null || !TAB_CLOUD.equals(tabType)) {
            return;
        }
        List<MediaRow> items = snapshot().getItems();
        for (int i = 0; i < items.size(); i++) {
            MediaRow row = items.get(i);
            if (row == null || !row.isCloudRow() || row.cloudItem == null) {
                continue;
            }
            MediaRow merged = mergeOverlay(copyRow(row));
            boolean downloaded = SyncStatusHelper.isDownloadedFast(context, localIndex, row.cloudItem);
            if (merged.downloaded == downloaded) {
                continue;
            }
            RowOverlayState state = RowOverlayState.fromRow(merged);
            state.downloaded = downloaded;
            putOverlay(row.stableKey(), state);
            notifyItemChanged(i, PAYLOAD_OVERLAY);
        }
    }

    public void updateSharedPaths(Set<String> sharedPaths) {
        List<MediaRow> items = snapshot().getItems();
        for (int i = 0; i < items.size(); i++) {
            MediaRow row = items.get(i);
            if (row == null) {
                continue;
            }
            MediaRow merged = mergeOverlay(copyRow(row));
            String path = merged.shareablePath();
            if (path == null) {
                continue;
            }
            RowOverlayState state = RowOverlayState.fromRow(merged);
            state.shared = sharedPaths.contains(path);
            putOverlay(row.stableKey(), state);
            notifyItemChanged(i, PAYLOAD_OVERLAY);
        }
    }

    public void seedOverlayFromPage(List<MediaRow> rows) {
        if (rows == null) {
            return;
        }
        for (MediaRow row : rows) {
            if (row != null) {
                putOverlay(row.stableKey(), RowOverlayState.fromRow(row));
            }
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        MediaRow row = mergeOverlay(copyRow(getItem(position)));
        if (row == null) {
            return;
        }
        if (payloads.contains(PAYLOAD_THUMB)) {
            bindThumbOnly(holder, row);
            return;
        }
        if (payloads.contains(PAYLOAD_OVERLAY)) {
            bindRowState(holder, row, position);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MediaRow row = mergeOverlay(copyRow(getItem(position)));
        if (row == null) {
            return;
        }

        holder.cbSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.cbSelect.setOnCheckedChangeListener(null);
        holder.cbSelect.setChecked(selectedKeys.contains(row.stableKey()));
        holder.cbSelect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) {
                return;
            }
            MediaRow current = getRowAt(pos);
            if (current == null) {
                return;
            }
            String key = current.stableKey();
            if (isChecked) {
                selectedKeys.add(key);
            } else {
                selectedKeys.remove(key);
            }
            listener.onSelectionToggle(pos, isChecked);
        });

        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onItemLongClick(pos);
                return true;
            }
            return false;
        });

        if (row.isCloudRow() || TAB_FAMILY.equals(tabType) || TAB_MY_SHARE.equals(tabType)) {
            bindCloudRow(holder, row, position);
        } else {
            bindLocalRow(holder, row, position);
        }
    }

    private void bindRowState(ViewHolder holder, MediaRow row, int position) {
        if (row.isCloudRow() || TAB_FAMILY.equals(tabType) || TAB_MY_SHARE.equals(tabType)) {
            bindCloudStatus(holder, row);
        } else {
            bindLocalStatus(holder, row);
            bindSyncOverlay(holder, row);
        }
    }

    private void bindCloudRow(ViewHolder holder, MediaRow row, int position) {
        hideSyncOverlay(holder);
        AlbumPhotoItem cloudItem = row.cloudItem;
        if (cloudItem == null) {
            return;
        }
        holder.tvFileName.setText(extractFileName(cloudItem.relativePath));
        bindThumbOnly(holder, row);
        bindCloudStatus(holder, row);
        holder.btnSyncOne.setVisibility(View.GONE);
        bindThumbClick(holder, row, position);
    }

    private void bindCloudStatus(ViewHolder holder, MediaRow row) {
        AlbumPhotoItem cloudItem = row.cloudItem;
        if (cloudItem == null) {
            return;
        }
        if (TAB_FAMILY.equals(tabType)) {
            String owner = cloudItem.ownerDisplayName != null
                    ? cloudItem.ownerDisplayName
                    : (cloudItem.ownerUsername != null ? cloudItem.ownerUsername : "");
            holder.tvStatus.setText(holder.itemView.getContext()
                    .getString(R.string.shared_by, owner));
            holder.tvStatus.setBackgroundResource(R.drawable.bg_status_shared);
            holder.tvStatus.setTextColor(ContextCompat.getColor(
                    holder.itemView.getContext(), R.color.shared_text));
        } else if (TAB_CLOUD.equals(tabType)) {
            if (row.downloaded) {
                holder.tvStatus.setText(R.string.status_downloaded);
                holder.tvStatus.setBackgroundResource(R.drawable.bg_status_synced);
                holder.tvStatus.setTextColor(ContextCompat.getColor(
                        holder.itemView.getContext(), R.color.synced_text));
            } else {
                holder.tvStatus.setText(R.string.status_not_downloaded);
                holder.tvStatus.setBackgroundResource(R.drawable.bg_status_cloud);
                holder.tvStatus.setTextColor(ContextCompat.getColor(
                        holder.itemView.getContext(), R.color.cloud_text));
            }
        } else {
            holder.tvStatus.setText(R.string.shared_badge);
            holder.tvStatus.setBackgroundResource(R.drawable.bg_status_shared);
            holder.tvStatus.setTextColor(ContextCompat.getColor(
                    holder.itemView.getContext(), R.color.shared_text));
        }
    }

    private void bindThumbOnly(ViewHolder holder, MediaRow row) {
        MediaGlideLoader.loadRow(holder.itemView.getContext(), row, holder.ivThumb);
    }

    private void bindLocalRow(ViewHolder holder, MediaRow row, int position) {
        MediaScanner.MediaItem item = row.item;
        if (item == null) {
            return;
        }
        holder.btnSyncOne.setVisibility(View.VISIBLE);
        holder.tvFileName.setText(item.fileName);
        bindThumbOnly(holder, row);
        bindLocalStatus(holder, row);
        bindThumbClick(holder, row, position);
        holder.btnSyncOne.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && !row.synced && !row.syncing) {
                listener.onSyncClick(row, pos);
            }
        });
        bindSyncOverlay(holder, row);
    }

    private void bindLocalStatus(ViewHolder holder, MediaRow row) {
        if (row.shared) {
            holder.tvStatus.setText(R.string.shared_badge);
            holder.tvStatus.setBackgroundResource(R.drawable.bg_status_shared);
            holder.tvStatus.setTextColor(ContextCompat.getColor(
                    holder.itemView.getContext(), R.color.shared_text));
        } else if (row.synced) {
            holder.tvStatus.setText(R.string.synced);
            holder.tvStatus.setBackgroundResource(R.drawable.bg_status_synced);
            holder.tvStatus.setTextColor(ContextCompat.getColor(
                    holder.itemView.getContext(), R.color.synced_text));
        } else {
            holder.tvStatus.setText(R.string.unsynced);
            holder.tvStatus.setBackgroundResource(R.drawable.bg_status_unsynced);
            holder.tvStatus.setTextColor(ContextCompat.getColor(
                    holder.itemView.getContext(), R.color.unsynced_text));
        }

        if (row.synced) {
            holder.btnSyncOne.setEnabled(false);
            holder.btnSyncOne.setText(R.string.synced);
            holder.btnSyncOne.setIconResource(R.drawable.ic_check);
        } else {
            holder.btnSyncOne.setEnabled(!row.syncing);
            holder.btnSyncOne.setText(row.syncing ? R.string.syncing_item : R.string.sync_single);
            holder.btnSyncOne.setIconResource(row.syncing ? R.drawable.ic_sync : R.drawable.ic_upload);
        }
    }

    private void bindSyncOverlay(ViewHolder holder, MediaRow row) {
        boolean show = row.syncing && row.syncProgress >= 0;
        holder.syncOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        holder.syncProgressContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        holder.syncProgressBar.setProgressCompat(row.syncProgress, true);
        holder.tvSyncProgress.setText(holder.itemView.getContext()
                .getString(R.string.upload_progress, row.syncProgress));
    }

    private void hideSyncOverlay(ViewHolder holder) {
        holder.syncOverlay.setVisibility(View.GONE);
        holder.syncProgressContainer.setVisibility(View.GONE);
    }

    private void bindThumbClick(ViewHolder holder, MediaRow row, int position) {
        holder.ivThumb.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) {
                return;
            }
            if (selectionMode) {
                MediaRow current = getRowAt(pos);
                if (current == null) {
                    return;
                }
                boolean selected = !selectedKeys.contains(current.stableKey());
                holder.cbSelect.setChecked(selected);
            } else {
                listener.onPreviewClick(pos);
            }
        });
    }

    private static String extractFileName(String path) {
        if (path == null) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        MediaGlideLoader.clear(holder.itemView.getContext(), holder.ivThumb);
        super.onViewRecycled(holder);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView tvFileName;
        TextView tvStatus;
        MaterialButton btnSyncOne;
        CheckBox cbSelect;
        View syncOverlay;
        FrameLayout syncProgressContainer;
        CircularProgressIndicator syncProgressBar;
        TextView tvSyncProgress;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.ivThumb);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            btnSyncOne = itemView.findViewById(R.id.btnSyncOne);
            cbSelect = itemView.findViewById(R.id.cbSelect);
            syncOverlay = itemView.findViewById(R.id.syncOverlay);
            syncProgressContainer = itemView.findViewById(R.id.syncProgressContainer);
            syncProgressBar = itemView.findViewById(R.id.syncProgressBar);
            tvSyncProgress = itemView.findViewById(R.id.tvSyncProgress);
        }
    }
}




























































