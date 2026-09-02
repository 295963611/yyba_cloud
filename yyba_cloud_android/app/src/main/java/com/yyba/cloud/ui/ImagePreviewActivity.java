package com.yyba.cloud.ui;



import android.content.Intent;

import android.net.Uri;

import android.os.Bundle;

import android.view.LayoutInflater;

import android.view.MenuItem;

import android.view.View;

import android.view.ViewGroup;

import android.widget.ImageView;

import android.widget.ProgressBar;

import android.widget.TextView;

import android.widget.Toast;



import androidx.annotation.NonNull;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import androidx.recyclerview.widget.RecyclerView;

import androidx.viewpager2.widget.ViewPager2;



import com.yyba.cloud.R;

import com.yyba.cloud.api.AlbumPhotoItem;

import com.yyba.cloud.api.ApiClient;

import com.yyba.cloud.util.CloudDownloadRunner;

import com.yyba.cloud.util.CloudImageLoader;

import com.yyba.cloud.util.PrefsManager;

import com.yyba.cloud.util.SyncRecordLookup;

import com.yyba.cloud.util.ThumbnailLoader;



import java.util.ArrayList;

import java.util.HashSet;

import java.util.List;

import java.util.Set;



public class ImagePreviewActivity extends AppCompatActivity {



    public static final String EXTRA_MODE = "mode";

    public static final String EXTRA_DOWNLOADED_CLOUD_PATH = "downloaded_cloud_path";

    public static final String MODE_LOCAL = "local";

    public static final String MODE_CLOUD = "cloud";



    private MaterialToolbar toolbar;

    private ViewPager2 viewPager;

    private TextView tvIndicator;

    private int itemCount;

    private boolean cloudMode;

    private PreviewAdapter previewAdapter;

    private ApiClient apiClient;

    private final Set<String> downloadedCloudPaths = new HashSet<>();



    @Override

    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_image_preview);



        MaterialToolbar toolbar = findViewById(R.id.toolbar);

        this.toolbar = toolbar;

        toolbar.setNavigationOnClickListener(v -> finish());



        viewPager = findViewById(R.id.viewPager);

        tvIndicator = findViewById(R.id.tvIndicator);



        if (savedInstanceState != null) {

            ArrayList<String> restored = savedInstanceState.getStringArrayList("downloaded_paths");

            if (restored != null) {

                downloadedCloudPaths.addAll(restored);

            }

        }



        String mode = getIntent().getStringExtra(EXTRA_MODE);

        cloudMode = MODE_CLOUD.equals(mode);

        if (cloudMode) {

            apiClient = new ApiClient(new PrefsManager(this));

            toolbar.inflateMenu(R.menu.menu_image_preview_cloud);

            toolbar.setOnMenuItemClickListener(this::onToolbarMenuClick);

            List<String> paths = MediaPreviewStore.getCloudPaths();

            int startIndex = MediaPreviewStore.getStartIndex();

            itemCount = paths != null ? paths.size() : 0;

            if (itemCount == 0) {

                Toast.makeText(this, "没有可预览的图片", Toast.LENGTH_SHORT).show();

                finish();

                return;

            }

            resolveCloudPreviewItemsAsync(paths, MediaPreviewStore.getCloudOwnerIds(), startIndex);

            return;

        }



        List<String> uris = MediaPreviewStore.getLocalUris();

        int startIndex = MediaPreviewStore.getStartIndex();

        itemCount = uris.size();

        setupPreviewAdapter(new PreviewAdapter(PreviewItem.localItems(uris), null), startIndex);

    }



    private void resolveCloudPreviewItemsAsync(List<String> paths,

                                               List<Long> ownerIds,

                                               int startIndex) {

        new Thread(() -> {

            List<AlbumPhotoItem> cloudItems = new ArrayList<>();

            for (int i = 0; i < paths.size(); i++) {

                String path = paths.get(i);

                if (path == null) {

                    continue;

                }

                AlbumPhotoItem item = new AlbumPhotoItem();

                item.relativePath = path;

                if (ownerIds != null && i < ownerIds.size() && ownerIds.get(i) != null) {

                    item.ownerUserId = String.valueOf(ownerIds.get(i));

                }

                cloudItems.add(item);

            }

            SyncRecordLookup.CloudPageLookup lookup = SyncRecordLookup.loadForCloudPage(

                    getApplicationContext(), cloudItems);

            List<PreviewItem> previewItems = new ArrayList<>(cloudItems.size());

            for (AlbumPhotoItem item : cloudItems) {

                String localUri = lookup.localUriIfPresent(item);

                Long ownerId = null;

                if (item.ownerUserId != null && !item.ownerUserId.isEmpty()) {

                    try {

                        ownerId = Long.parseLong(item.ownerUserId);

                    } catch (NumberFormatException ignored) {

                    }

                }

                previewItems.add(new PreviewItem(localUri, item.relativePath, ownerId));

            }

            runOnUiThread(() -> setupPreviewAdapter(

                    new PreviewAdapter(previewItems, apiClient), startIndex));

        }).start();

    }



    private void setupPreviewAdapter(PreviewAdapter adapter, int startIndex) {

        previewAdapter = adapter;



        if (itemCount == 0) {

            Toast.makeText(this, "没有可预览的图片", Toast.LENGTH_SHORT).show();

            finish();

            return;

        }



        viewPager.setAdapter(adapter);

        viewPager.setOffscreenPageLimit(1);

        if (startIndex > 0 && startIndex < itemCount) {

            viewPager.setCurrentItem(startIndex, false);

        }

        updateIndicator(viewPager.getCurrentItem());

        updateDownloadMenuVisibility(viewPager.getCurrentItem());

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {

            @Override

            public void onPageSelected(int position) {

                updateIndicator(position);

                updateDownloadMenuVisibility(position);

            }

        });

    }



    @Override

    protected void onSaveInstanceState(@NonNull Bundle outState) {

        super.onSaveInstanceState(outState);

        if (!downloadedCloudPaths.isEmpty()) {

            outState.putStringArrayList("downloaded_paths", new ArrayList<>(downloadedCloudPaths));

        }

    }



    @Override

    protected void onDestroy() {

        if (!downloadedCloudPaths.isEmpty()) {

            Intent data = new Intent();

            data.putExtra(EXTRA_DOWNLOADED_CLOUD_PATH,

                    downloadedCloudPaths.iterator().next());

            setResult(RESULT_OK, data);

        }

        super.onDestroy();

    }



    private void updateIndicator(int position) {

        tvIndicator.setText((position + 1) + " / " + itemCount);

    }



    private boolean onToolbarMenuClick(MenuItem item) {

        if (item.getItemId() == R.id.action_download) {

            downloadCurrentCloudImage();

            return true;

        }

        return false;

    }



    private void downloadCurrentCloudImage() {

        if (!cloudMode || previewAdapter == null || apiClient == null) {

            return;

        }

        int index = viewPager.getCurrentItem();

        PreviewItem item = previewAdapter.getItem(index);

        if (item == null || item.cloudPath == null) {

            return;

        }

        if ((item.localUri != null && !item.localUri.isEmpty())

                || downloadedCloudPaths.contains(item.cloudPath)) {

            Toast.makeText(this, R.string.cloud_download_already_synced, Toast.LENGTH_SHORT).show();

            return;

        }

        String cloudPath = item.cloudPath;

        CloudDownloadRunner.startGallerySync(this, apiClient, cloudPath, item.cloudOwnerId,

                (name, saved) -> runOnUiThread(() -> onCloudDownloadSuccess(cloudPath)));

    }



    private void onCloudDownloadSuccess(String cloudPath) {

        if (cloudPath == null || cloudPath.isEmpty()) {

            return;

        }

        downloadedCloudPaths.add(cloudPath);

        if (toolbar != null) {

            toolbar.setSubtitle(R.string.synced);

        }

        updateDownloadMenuVisibility(viewPager.getCurrentItem());

    }



    private void updateDownloadMenuVisibility(int index) {

        if (!cloudMode || toolbar == null || previewAdapter == null) {

            return;

        }

        PreviewItem item = previewAdapter.getItem(index);

        MenuItem downloadItem = toolbar.getMenu().findItem(R.id.action_download);

        if (downloadItem == null) {

            return;

        }

        boolean alreadyDownloaded = item != null && item.cloudPath != null

                && (downloadedCloudPaths.contains(item.cloudPath)

                || (item.localUri != null && !item.localUri.isEmpty()));

        downloadItem.setVisible(!alreadyDownloaded);

    }



    static class PreviewItem {

        final String localUri;

        final String cloudPath;

        final Long cloudOwnerId;



        PreviewItem(String localUri, String cloudPath, Long cloudOwnerId) {

            this.localUri = localUri;

            this.cloudPath = cloudPath;

            this.cloudOwnerId = cloudOwnerId;

        }



        static List<PreviewItem> localItems(List<String> uris) {

            List<PreviewItem> items = new ArrayList<>();

            for (String uri : uris) {

                items.add(new PreviewItem(uri, null, null));

            }

            return items;

        }



        static List<PreviewItem> cloudItems(List<String> paths, List<Long> ownerIds) {

            List<PreviewItem> items = new ArrayList<>();

            if (paths == null) return items;

            for (int i = 0; i < paths.size(); i++) {

                String path = paths.get(i);

                if (path != null) {

                    Long ownerId = ownerIds != null && i < ownerIds.size() ? ownerIds.get(i) : null;

                    items.add(new PreviewItem(null, path, ownerId));

                }

            }

            return items;

        }



        boolean isCloud() {

            return cloudPath != null;

        }

    }



    static class PreviewAdapter extends RecyclerView.Adapter<PreviewAdapter.PageHolder> {



        private final List<PreviewItem> items;

        private final ApiClient apiClient;



        PreviewAdapter(List<PreviewItem> items, ApiClient apiClient) {

            this.items = items;

            this.apiClient = apiClient;

        }



        PreviewItem getItem(int index) {

            if (index < 0 || index >= items.size()) {

                return null;

            }

            return items.get(index);

        }



        @NonNull

        @Override

        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {

            View view = LayoutInflater.from(parent.getContext())

                    .inflate(R.layout.item_preview_page, parent, false);

            return new PageHolder(view);

        }



        @Override

        public void onBindViewHolder(@NonNull PageHolder holder, int position) {

            PreviewItem item = items.get(position);

            holder.progress.setVisibility(View.VISIBLE);

            holder.ivPreview.setImageDrawable(null);

            holder.ivPreview.setTag(null);

            Runnable hideProgress = () -> {

                if (holder.progress != null) {

                    holder.progress.setVisibility(View.GONE);

                }

            };

            if (item.localUri != null && !item.localUri.isEmpty()) {

                ThumbnailLoader.loadFull(holder.itemView.getContext(),

                        Uri.parse(item.localUri), holder.ivPreview);

                holder.itemView.postDelayed(hideProgress, 800);

            } else if (item.isCloud()) {

                if (item.cloudPath == null) {

                    hideProgress.run();

                    return;

                }

                CloudImageLoader.loadFull(apiClient, item.cloudPath, item.cloudOwnerId,

                        holder.ivPreview, hideProgress);

            } else {

                ThumbnailLoader.loadFull(holder.itemView.getContext(),

                        Uri.parse(item.localUri), holder.ivPreview);

                holder.itemView.postDelayed(hideProgress, 800);

            }

        }



        @Override

        public int getItemCount() {

            return items.size();

        }



        static class PageHolder extends RecyclerView.ViewHolder {

            ImageView ivPreview;

            ProgressBar progress;



            PageHolder(@NonNull View itemView) {

                super(itemView);

                ivPreview = itemView.findViewById(R.id.ivPreview);

                progress = itemView.findViewById(R.id.progress);

            }

        }

    }

}

