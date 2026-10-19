package com.yyba.cloud.ui;

import android.content.res.ColorStateList;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.yyba.cloud.R;
import com.yyba.cloud.backup.FileEntry;
import com.yyba.cloud.util.DocumentTypes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 文件列表适配器：MediaStore 分组列表 + 文件列表 */
public class PhoneBackupAdapter extends RecyclerView.Adapter<PhoneBackupAdapter.ViewHolder> {

    public interface Listener {
        void onItemClick(FileEntry entry, int position);
        void onItemLongClick(FileEntry entry, int position);
    }

    private static final RequestOptions VIDEO_THUMB_OPTIONS = new RequestOptions()
            .centerCrop()
            .override(320, 320)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .dontAnimate();

    private final List<FileEntry> items = new ArrayList<>();
    private final Set<String> selectedKeys = new HashSet<>();
    private final Listener listener;
    private boolean selectionMode = false;

    public PhoneBackupAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<FileEntry> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void enterSelectionMode() {
        selectionMode = true;
        notifyDataSetChanged();
    }

    public void exitSelectionMode() {
        selectionMode = false;
        selectedKeys.clear();
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public boolean toggleSelection(String key) {
        for (FileEntry e : items) {
            if (key.equals(e.getSelectionKey())) {
                if (e.backupStatus == FileEntry.BackupStatus.BACKED_UP) return false;
                break;
            }
        }
        if (selectedKeys.contains(key)) selectedKeys.remove(key);
        else selectedKeys.add(key);
        notifyDataSetChanged();
        return selectedKeys.contains(key);
    }

    public void selectAllVisible() {
        for (FileEntry entry : items) {
            if (!entry.isDirectory && entry.backupStatus != FileEntry.BackupStatus.BACKED_UP) {
                selectedKeys.add(entry.getSelectionKey());
            }
        }
        notifyDataSetChanged();
    }

    public Set<String> getSelectedKeys() {
        return new HashSet<>(selectedKeys);
    }

    public int getSelectedCount() {
        return selectedKeys.size();
    }

    public List<FileEntry> getCurrentEntries() {
        return items;
    }

    static boolean isImage(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif")
                || lower.endsWith(".bmp") || lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    static boolean isVideo(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".wmv") || lower.endsWith(".flv")
                || lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.endsWith(".m4v");
    }

    static boolean isAudio(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac")
                || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".m4a")
                || lower.endsWith(".wma");
    }

    /** 返回文档扩展名对应的专用图标资源 */
    static int documentIcon(String fileName) {
        if (fileName == null) return R.drawable.ic_document;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return R.drawable.ic_pdf;
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return R.drawable.ic_word;
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return R.drawable.ic_excel;
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return R.drawable.ic_ppt;
        if (lower.endsWith(".txt")) return R.drawable.ic_txt;
        return R.drawable.ic_document;
    }

    static String documentMimeType(String fileName) {
        if (fileName == null) return "*/*";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".txt")) return "text/plain";
        return "*/*";
    }

    public List<String> getImageUris() {
        List<String> uris = new ArrayList<>();
        for (FileEntry entry : items) {
            if (!entry.isDirectory && isImage(entry.name) && entry.mediaUri != null) {
                uris.add(entry.mediaUri.toString());
            }
        }
        return uris;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_phone_backup, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FileEntry entry = items.get(position);
        holder.bind(entry, position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvName;
        final TextView tvSubtitle;
        final TextView tvBackupStatus;
        final CheckBox cbSelect;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvSubtitle = itemView.findViewById(R.id.tvSubtitle);
            tvBackupStatus = itemView.findViewById(R.id.tvBackupStatus);
            cbSelect = itemView.findViewById(R.id.cbSelect);
            // 复选框仅作只读指示器：不消费触摸事件，点击落到整行 → onItemClick → toggleSelection，
            // 否则点击复选框只会改它自己的视觉勾选态，selectedKeys 不会更新。
            cbSelect.setClickable(false);
            cbSelect.setFocusable(false);
        }

        void bind(FileEntry entry, int position) {
            tvName.setText(entry.name);

            if (entry.isDirectory) {
                bindDirectory(entry);
            } else if (isImage(entry.name)) {
                bindImage(entry);
            } else if (isVideo(entry.name)) {
                bindVideo(entry);
            } else if (isAudio(entry.name)) {
                bindAudio(entry);
            } else if (DocumentTypes.isDocumentFileName(entry.name)) {
                bindDocument(entry);
            } else {
                bindGenericFile(entry);
            }

            // 备份状态
            if (!entry.isDirectory && entry.backupStatus == FileEntry.BackupStatus.BACKED_UP) {
                tvBackupStatus.setVisibility(View.VISIBLE);
                String sourceLabel = "album".equals(entry.backupSource) ? "相册已备份" : "已备份";
                tvBackupStatus.setText(sourceLabel);
            } else {
                tvBackupStatus.setVisibility(View.GONE);
            }

            // 选择框
            if (selectionMode && !entry.isDirectory) {
                cbSelect.setVisibility(View.VISIBLE);
                if (entry.backupStatus == FileEntry.BackupStatus.BACKED_UP) {
                    cbSelect.setEnabled(false);
                    cbSelect.setChecked(true);
                } else {
                    cbSelect.setEnabled(true);
                    cbSelect.setChecked(selectedKeys.contains(entry.getSelectionKey()));
                }
            } else {
                cbSelect.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(entry, getAdapterPosition());
            });
            itemView.setOnLongClickListener(v -> {
                if (listener != null) listener.onItemLongClick(entry, getAdapterPosition());
                return true;
            });
        }

        private void bindDirectory(FileEntry entry) {
            ImageViewCompat.setImageTintList(ivIcon,
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.getContext(),
                            R.color.primary)));
            ivIcon.setImageResource(R.drawable.ic_folder);
            StringBuilder sub = new StringBuilder();
            if (entry.childDirCount > 0) sub.append(entry.childDirCount).append(" 个子目录");
            if (entry.totalFileCount > 0) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(entry.totalFileCount).append(" 个文件");
            }
            tvSubtitle.setText(sub.length() > 0 ? sub.toString() : "空目录");
        }

        private void bindImage(FileEntry entry) {
            ImageViewCompat.setImageTintList(ivIcon, null);
            Glide.with(itemView.getContext())
                    .load(entry.mediaUri)
                    .placeholder(R.drawable.ic_file)
                    .centerCrop()
                    .into(ivIcon);
            tvSubtitle.setText(formatSize(entry.size));
        }

        private void bindVideo(FileEntry entry) {
            ImageViewCompat.setImageTintList(ivIcon, null);
            Glide.with(itemView.getContext())
                    .load(entry.mediaUri)
                    .apply(VIDEO_THUMB_OPTIONS)
                    .placeholder(R.drawable.ic_video)
                    .error(R.drawable.ic_video)
                    .into(ivIcon);
            tvSubtitle.setText(formatSize(entry.size));
        }

        private void bindAudio(FileEntry entry) {
            ImageViewCompat.setImageTintList(ivIcon,
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.getContext(),
                            R.color.primary)));
            ivIcon.setImageResource(R.drawable.ic_audio);
            tvSubtitle.setText(formatSize(entry.size));
        }

        private void bindDocument(FileEntry entry) {
            ImageViewCompat.setImageTintList(ivIcon, null);
            ivIcon.setImageResource(documentIcon(entry.name));
            tvSubtitle.setText(formatSize(entry.size));
        }

        private void bindGenericFile(FileEntry entry) {
            ImageViewCompat.setImageTintList(ivIcon,
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.getContext(),
                            R.color.primary)));
            ivIcon.setImageResource(R.drawable.ic_file);
            tvSubtitle.setText(formatSize(entry.size));
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
