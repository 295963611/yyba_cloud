package com.yyba.cloud.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yyba.cloud.R;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.api.CloudFile;
import com.yyba.cloud.util.CloudImageLoader;
import com.yyba.cloud.util.ThumbBitmapCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 回收站列表：图片/视频以相册网格缩略图展示，其他文件为列表行 */
public class TrashAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_MEDIA = 0;
    private static final int TYPE_FILE = 1;
    private static final int GRID_SPAN = 3;

    public interface Listener {
        void onItemClick(CloudFile file, int position);
        void onItemLongClick(CloudFile file, int position);
    }

    private final Listener listener;
    private final ApiClient apiClient;
    private final List<CloudFile> items = new ArrayList<>();
    private static boolean scrollPaused;

    public TrashAdapter(Listener listener, ApiClient apiClient) {
        this.listener = listener;
        this.apiClient = apiClient;
    }

    public static void setScrollPaused(boolean paused) {
        scrollPaused = paused;
        ThumbBitmapCache.setPaused(paused);
    }

    public void setItems(List<CloudFile> files) {
        items.clear();
        if (files != null) {
            items.addAll(files);
        }
        notifyDataSetChanged();
    }

    public CloudFile getItem(int position) {
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position);
    }

    public void refreshVisibleThumbnails(GridLayoutManager layoutManager) {
        if (scrollPaused || layoutManager == null) {
            return;
        }
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return;
        }
        for (int i = first; i <= last && i < items.size(); i++) {
            if (isMedia(items.get(i))) {
                notifyItemChanged(i);
            }
        }
    }

    public static boolean isPhoto(CloudFile file) {
        if (file == null || file.directory) {
            return false;
        }
        if ("PHOTO".equalsIgnoreCase(file.fileType) || "IMAGE".equalsIgnoreCase(file.fileType)) {
            return true;
        }
        return file.mimeType != null && file.mimeType.startsWith("image/");
    }

    public static boolean isVideo(CloudFile file) {
        if (file == null || file.directory) {
            return false;
        }
        if ("VIDEO".equalsIgnoreCase(file.fileType)) {
            return true;
        }
        return file.mimeType != null && file.mimeType.startsWith("video/");
    }

    public static boolean isMedia(CloudFile file) {
        return isPhoto(file) || isVideo(file);
    }

    @Override
    public int getItemViewType(int position) {
        return isMedia(items.get(position)) ? TYPE_MEDIA : TYPE_FILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_MEDIA) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_trash_media, parent, false);
            return new MediaHolder(view);
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_cloud_file, parent, false);
        return new FileHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        CloudFile file = items.get(position);
        if (holder instanceof MediaHolder) {
            bindMedia((MediaHolder) holder, file, position);
        } else if (holder instanceof FileHolder) {
            bindFile((FileHolder) holder, file, position);
        }
    }

    private void bindMedia(MediaHolder holder, CloudFile file, int position) {
        holder.tvFileName.setText(file.name != null ? file.name : "");
        holder.tvDeletedAt.setText(file.deletedAt != null ? file.deletedAt : "");

        boolean video = isVideo(file);
        holder.ivVideoBadge.setVisibility(video ? View.VISIBLE : View.GONE);

        if (file.relativePath != null) {
            if (video) {
                CloudImageLoader.loadThumbnailWithFallback(apiClient, file.relativePath, null,
                        holder.ivThumb, R.drawable.ic_video);
            } else {
                CloudImageLoader.loadThumbnailWithFallback(apiClient, file.relativePath, null,
                        holder.ivThumb, R.drawable.ic_photo);
            }
        } else {
            holder.ivThumb.setImageResource(video ? R.drawable.ic_video : R.drawable.ic_photo);
        }

        bindClicks(holder.itemView, file, position);
    }

    private void bindFile(FileHolder holder, CloudFile file, int position) {
        holder.ivThumb.setVisibility(View.GONE);
        holder.ivTypeIcon.setVisibility(View.VISIBLE);
        int iconRes = iconForType(file.fileType);
        holder.ivTypeIcon.setImageResource(iconRes);

        holder.tvName.setText(file.name != null ? file.name : "");
        holder.tvMeta.setText(file.deletedAt != null ? file.deletedAt : formatSize(file.size));

        bindClicks(holder.itemView, file, position);
    }

    private void bindClicks(View itemView, CloudFile file, int position) {
        itemView.setOnClickListener(v -> listener.onItemClick(file, position));
        itemView.setOnLongClickListener(v -> {
            listener.onItemLongClick(file, position);
            return true;
        });
    }

    private int iconForType(String type) {
        if (type == null) {
            return R.drawable.ic_file;
        }
        switch (type.toUpperCase(Locale.ROOT)) {
            case "AUDIO":
                return R.drawable.ic_audio;
            case "DOCUMENT":
                return R.drawable.ic_document;
            default:
                return R.drawable.ic_file;
        }
    }

    private String formatSize(Long size) {
        if (size == null || size <= 0) {
            return "";
        }
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", size / 1024.0 / 1024.0);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public int getGridSpanCount() {
        return GRID_SPAN;
    }

    public GridLayoutManager.SpanSizeLookup createSpanSizeLookup() {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return getItemViewType(position) == TYPE_MEDIA ? 1 : GRID_SPAN;
            }
        };
    }

    static class MediaHolder extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        ImageView ivVideoBadge;
        TextView tvFileName;
        TextView tvDeletedAt;

        MediaHolder(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.ivThumb);
            ivVideoBadge = itemView.findViewById(R.id.ivVideoBadge);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvDeletedAt = itemView.findViewById(R.id.tvDeletedAt);
        }
    }

    static class FileHolder extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        ImageView ivTypeIcon;
        TextView tvName;
        TextView tvMeta;

        FileHolder(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.ivThumb);
            ivTypeIcon = itemView.findViewById(R.id.ivTypeIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvMeta = itemView.findViewById(R.id.tvMeta);
        }
    }
}
