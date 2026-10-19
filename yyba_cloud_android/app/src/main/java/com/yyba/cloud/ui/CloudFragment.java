package com.yyba.cloud.ui;



import android.content.Context;
import android.content.Intent;

import android.os.Bundle;
import android.util.Log;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import android.widget.TextView;

import android.widget.Toast;



import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import androidx.appcompat.app.AlertDialog;

import androidx.fragment.app.Fragment;

import androidx.recyclerview.widget.LinearLayoutManager;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.yyba.cloud.R;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.api.CloudFile;
import com.yyba.cloud.util.CloudDownloadRunner;
import com.yyba.cloud.util.PrefsManager;
import com.yyba.cloud.util.ServerSyncCache;
import com.yyba.cloud.util.TrashConfigHelper;



import java.util.ArrayList;

import java.util.List;

import java.util.Locale;



public class CloudFragment extends Fragment implements CloudFileAdapter.Listener {



    private RecyclerView rvCloudFiles;

    private TextView tvBreadcrumb;

    private TextView tvCloudEmpty;

    private CloudFileAdapter adapter;

    private ApiClient apiClient;

    private String currentPath = "";

    private boolean loading;

    private String parentPathOf(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        if (idx <= 0) {
            return "";
        }
        return path.substring(0, idx);
    }

    public boolean canGoBack() {
        Log.d("CloudFragment", "canGoBack: currentPath='" + currentPath + "'");
        return !currentPath.isEmpty();
    }

    public void goBack() {
        Log.d("CloudFragment", "goBack: from='" + currentPath + "' to='" + parentPathOf(currentPath) + "'");
        loadFiles(parentPathOf(currentPath));
    }



    @Nullable

    @Override

    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,

                             @Nullable Bundle savedInstanceState) {

        return inflater.inflate(R.layout.fragment_cloud, container, false);

    }



    @Override

    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

        super.onViewCreated(view, savedInstanceState);

        rvCloudFiles = view.findViewById(R.id.rvCloudFiles);

        tvBreadcrumb = view.findViewById(R.id.tvBreadcrumb);

        tvCloudEmpty = view.findViewById(R.id.tvCloudEmpty);

        MaterialButton btnOpenTrash = view.findViewById(R.id.btnOpenTrash);
        btnOpenTrash.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), TrashActivity.class)));

        MaterialButton btnPhoneBackup = view.findViewById(R.id.btnPhoneBackup);
        btnPhoneBackup.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), PhoneBackupActivity.class)));

        apiClient = new ApiClient(new PrefsManager(requireContext()));

        adapter = new CloudFileAdapter(this, apiClient);

        rvCloudFiles.setLayoutManager(new LinearLayoutManager(requireContext()));

        rvCloudFiles.setAdapter(adapter);

        tvBreadcrumb.setOnClickListener(v -> showPathPicker());

        if (savedInstanceState != null) {

            currentPath = savedInstanceState.getString("currentPath", "");

        }

        updateBreadcrumb();

        if (savedInstanceState == null) {

            loadFiles("");

        } else {

            loadFiles(currentPath);

        }

    }



    @Override

    public void onSaveInstanceState(@NonNull Bundle outState) {

        super.onSaveInstanceState(outState);

        outState.putString("currentPath", currentPath);

    }



    private void runOnUiIfAdded(Runnable action) {
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (isAdded()) {
                action.run();
            }
        });
    }



    private void loadFiles(String path) {

        Log.d("CloudFragment", "loadFiles: path='" + path + "' loading=" + loading);

        if (loading) return;

        loading = true;

        currentPath = path == null ? "" : path;

        Log.d("CloudFragment", "loadFiles: currentPath set to '" + currentPath + "'");

        updateBreadcrumb();

        tvCloudEmpty.setVisibility(View.GONE);

        new Thread(() -> {

            try {

                List<CloudFile> files = apiClient.listFiles(currentPath);

                if (!isAdded()) return;

                runOnUiIfAdded(() -> {

                    if (!isAdded()) return;

                    loading = false;

                    adapter.setItems(files);

                    tvCloudEmpty.setText(R.string.cloud_empty);

                    tvCloudEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);

                });

            } catch (Exception e) {

                if (!isAdded()) return;

                runOnUiIfAdded(() -> {

                    if (!isAdded()) return;

                    loading = false;

                    Toast.makeText(requireContext(),

                            getString(R.string.cloud_load_failed, e.getMessage()),

                            Toast.LENGTH_LONG).show();

                });

            }

        }).start();

    }



    private void updateBreadcrumb() {

        if (currentPath.isEmpty()) {

            tvBreadcrumb.setText(getString(R.string.cloud_root));

            return;

        }

        StringBuilder sb = new StringBuilder(getString(R.string.cloud_root));

        for (String part : currentPath.split("/")) {

            if (!part.isEmpty()) {

                sb.append(" / ").append(part);

            }

        }

        tvBreadcrumb.setText(sb.toString());

    }



    private void showPathPicker() {

        List<String> pathStack = new ArrayList<>();

        List<String> labels = new ArrayList<>();

        pathStack.add("");

        labels.add(getString(R.string.cloud_root));

        if (!currentPath.isEmpty()) {

            String[] parts = currentPath.split("/");

            StringBuilder acc = new StringBuilder();

            for (String part : parts) {

                if (part.isEmpty()) continue;

                if (acc.length() > 0) acc.append('/');

                acc.append(part);

                pathStack.add(acc.toString());

                labels.add(getString(R.string.cloud_root) + " / " + acc.toString().replace("/", " / "));

            }

        }

        new AlertDialog.Builder(requireContext())

                .setTitle(R.string.cloud_nav_title)

                .setItems(labels.toArray(new String[0]), (d, which) -> loadFiles(pathStack.get(which)))

                .show();

    }



    @Override

    public void onItemClick(CloudFile file, int position) {

        if (file == null) return;

        Log.d("CloudFragment", "onItemClick: name='" + file.name + "' directory=" + file.directory + " relativePath='" + file.relativePath + "'");

        if (file.directory) {

            if (file.relativePath != null) {

                loadFiles(file.relativePath);

            }

            return;

        }

        openPreview(file);

    }



    @Override

    public void onItemLongClick(CloudFile file, int position) {

        if (file == null || file.directory) return;

        new AlertDialog.Builder(requireContext())

                .setTitle(file.name)

                .setItems(new CharSequence[]{

                        getString(R.string.cloud_action_download),

                        getString(R.string.cloud_action_delete)

                }, (d, which) -> {

                    if (which == 0) downloadFile(file);

                    else confirmDelete(file);

                })

                .show();

    }



    private void openPreview(CloudFile file) {

        if (file.relativePath == null) {

            Toast.makeText(requireContext(), "文件路径无效", Toast.LENGTH_SHORT).show();

            return;

        }

        if (CloudFileAdapter.isImage(file)) {

            openImagePreview(file);

            return;

        }

        if (isVideo(file)) {

            Intent intent = new Intent(requireContext(), VideoPreviewActivity.class);

            intent.putExtra(VideoPreviewActivity.EXTRA_CLOUD_PATH, file.relativePath);

            startActivity(intent);

            return;

        }

        if (isAudio(file)) {

            Intent intent = new Intent(requireContext(), VideoPreviewActivity.class);

            intent.putExtra(VideoPreviewActivity.EXTRA_CLOUD_PATH, file.relativePath);

            intent.putExtra(VideoPreviewActivity.EXTRA_AUDIO_MODE, true);

            startActivity(intent);

            return;

        }

        Toast.makeText(requireContext(), R.string.cloud_preview_unsupported, Toast.LENGTH_SHORT).show();

    }



    private boolean isVideo(CloudFile file) {

        if ("VIDEO".equalsIgnoreCase(file.fileType)) return true;

        String name = file.name != null ? file.name.toLowerCase(Locale.ROOT) : "";

        return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv") || name.endsWith(".3gp");

    }



    private boolean isAudio(CloudFile file) {

        String name = file.name != null ? file.name.toLowerCase(Locale.ROOT) : "";

        return name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".wav")

                || name.endsWith(".aac") || name.endsWith(".flac") || name.endsWith(".ogg");

    }



    private void downloadFile(CloudFile file) {

        if (file.relativePath == null || file.name == null) return;

        CloudDownloadRunner.start(this, apiClient, file.name, file.relativePath, null);

    }



    private void confirmDelete(CloudFile file) {

        int days = TrashConfigHelper.getRetentionDays(requireContext());

        new AlertDialog.Builder(requireContext())

                .setMessage(getString(R.string.cloud_delete_confirm, file.name, days))

                .setPositiveButton(R.string.delete, (d, w) -> deleteFile(file))

                .setNegativeButton(android.R.string.cancel, null)

                .show();

    }



    private void deleteFile(CloudFile file) {

        if (file.relativePath == null) return;

        new Thread(() -> {

            try {

                apiClient.deleteFile(file.relativePath);

                Context app = requireContext().getApplicationContext();
                ServerSyncCache.removePath(app, file.relativePath);

                if (!isAdded()) return;

                runOnUiIfAdded(() -> {

                    Toast.makeText(requireContext(), R.string.cloud_delete_ok, Toast.LENGTH_SHORT).show();

                    loadFiles(currentPath);

                });

            } catch (Exception e) {

                if (!isAdded()) return;

                runOnUiIfAdded(() ->
                        Toast.makeText(requireContext(),

                                getString(R.string.cloud_delete_failed, e.getMessage()),

                                Toast.LENGTH_LONG).show());

            }

        }).start();

    }



    private void openImagePreview(CloudFile selected) {

        if (!isAdded() || selected.relativePath == null) return;



        List<CloudFile> images = adapter.getImageFiles();

        ArrayList<String> paths = new ArrayList<>();

        int startIndex = 0;

        for (CloudFile img : images) {

            if (selected.relativePath.equals(img.relativePath)) {

                startIndex = paths.size();

            }

            paths.add(img.relativePath);

        }

        if (paths.isEmpty()) {

            paths.add(selected.relativePath);

            startIndex = 0;

        }



        try {

            MediaPreviewStore.setCloudSession(paths, startIndex);

            Intent intent = new Intent(requireContext(), ImagePreviewActivity.class);

            intent.putExtra(ImagePreviewActivity.EXTRA_MODE, ImagePreviewActivity.MODE_CLOUD);

            startActivity(intent);

        } catch (Exception e) {

            Toast.makeText(requireContext(), "无法打开预览", Toast.LENGTH_SHORT).show();

        }

    }



    @Override

    public void onResume() {

        super.onResume();

        if (adapter.getItemCount() == 0 && !loading) {

            loadFiles(currentPath);

        }

    }

}


