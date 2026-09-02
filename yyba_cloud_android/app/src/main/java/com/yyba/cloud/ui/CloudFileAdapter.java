package com.yyba.cloud.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.yyba.cloud.R;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.api.CloudFile;
import com.yyba.cloud.util.CloudImageLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CloudFileAdapter extends RecyclerView.Adapter<CloudFileAdapter.ViewHolder> {

    public interface Listener {
        void onItemClick(CloudFile file, int position);
        void onItemLongClick(CloudFile file, int position);
    }

    private final Listener listener;
    private final ApiClient apiClient;
    private final List<CloudFile> items = new ArrayList<>();
    private boolean trashMode;

    public CloudFileAdapter(Listener listener, ApiClient apiClient) {
        this.listener = listener;
        this.apiClient = apiClient;
    }

    public void setTrashMode(boolean trashMode) {
        this.trashMode = trashMode;
    }

    public void setItems(List<CloudFile> files) {
        items.clear();
        if (files != null) items.addAll(files);
        notifyDataSetChanged();
    }

    public CloudFile getItem(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    public List<CloudFile> getImageFiles() {
        List<CloudFile> images = new ArrayList<>();
        for (CloudFile file : items) {
            if (!file.directory && isImage(file) && file.relativePath != null) {
                images.add(file);
            }
        }
        return images;
    }

    public static boolean isImage(CloudFile file) {
        if (file == null || file.directory) return false;
        if ("IMAGE".equalsIgnoreCase(file.fileType) || "PHOTO".equalsIgnoreCase(file.fileType)) {
            return true;
        }
        return file.mimeType != null && file.mimeType.startsWith("image/");
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_cloud_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CloudFile file = items.get(position);
        boolean image = isImage(file);

        if (image && file.relativePath != null) {
            holder.ivThumb.setVisibility(View.VISIBLE);
            holder.ivTypeIcon.setVisibility(View.GONE);
            CloudImageLoader.loadThumbnail(apiClient, file.relativePath, holder.ivThumb);
        } else {
            holder.ivThumb.setVisibility(View.GONE);
            holder.ivTypeIcon.setVisibility(View.VISIBLE);
            @DrawableRes int iconRes = file.directory ? R.drawable.ic_folder : iconForType(file.fileType);
            holder.ivTypeIcon.setImageResource(iconRes);
            int tint = ContextCompat.getColor(holder.itemView.getContext(),
                    file.directory ? R.color.primary : R.color.icon_tint);
            holder.ivTypeIcon.setColorFilter(tint);
        }

        holder.tvName.setText(file.name != null ? file.name : "");
        if (file.directory) {
            holder.tvMeta.setText("文件夹");
        } else if (trashMode && file.deletedAt != null && !file.deletedAt.isEmpty()) {
            holder.tvMeta.setText(file.deletedAt);
        } else {
            holder.tvMeta.setText(formatSize(file.size));
        }
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            CloudFile clicked = getItem(pos);
            if (clicked != null) {
                listener.onItemClick(clicked, pos);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            CloudFile clicked = getItem(pos);
            if (clicked != null) {
                listener.onItemLongClick(clicked, pos);
                return true;
            }
            return false;
        });
    }

    @DrawableRes
    private int iconForType(String type) {
        if (type == null) return R.drawable.ic_file;
        switch (type.toUpperCase(Locale.ROOT)) {
            case "IMAGE":
            case "PHOTO": return R.drawable.ic_photo;
            case "VIDEO": return R.drawable.ic_video;
            case "AUDIO": return R.drawable.ic_audio;
            default: return R.drawable.ic_document;
        }
    }

    private String formatSize(Long size) {
        if (size == null || size <= 0) return "";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", size / 1024.0 / 1024.0);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        ImageView ivTypeIcon;
        TextView tvName;
        TextView tvMeta;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.ivThumb);
            ivTypeIcon = itemView.findViewById(R.id.ivTypeIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvMeta = itemView.findViewById(R.id.tvMeta);
        }
    }
}
