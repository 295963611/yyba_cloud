package com.yyba.cloud.ui;



import android.os.Bundle;

import android.view.View;

import android.widget.TextView;

import android.widget.Toast;



import androidx.appcompat.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;

import androidx.recyclerview.widget.GridLayoutManager;

import androidx.recyclerview.widget.RecyclerView;



import com.google.android.material.appbar.MaterialToolbar;

import com.yyba.cloud.R;

import com.yyba.cloud.api.ApiClient;

import com.yyba.cloud.api.CloudFile;

import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.util.PrefsManager;

import com.yyba.cloud.util.ServerSyncCache;

import com.yyba.cloud.util.TrashConfigHelper;



import java.util.List;



/** 回收站：恢复与永久删除 */

public class TrashActivity extends AppCompatActivity implements TrashAdapter.Listener {



    private ApiClient apiClient;

    private TrashAdapter adapter;

    private GridLayoutManager layoutManager;

    private TextView tvTrashEmpty;

    private int retentionDays;



    @Override

    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_trash);



        MaterialToolbar toolbar = findViewById(R.id.toolbar);

        toolbar.setNavigationOnClickListener(v -> finish());



        TextView tvTrashHint = findViewById(R.id.tvTrashHint);

        tvTrashEmpty = findViewById(R.id.tvTrashEmpty);

        RecyclerView rvTrash = findViewById(R.id.rvTrash);



        retentionDays = TrashConfigHelper.getRetentionDays(this);

        tvTrashHint.setText(getString(R.string.cloud_trash_hint_days, retentionDays));



        TrashAdapter.setScrollPaused(false);

        apiClient = new ApiClient(new PrefsManager(this));

        adapter = new TrashAdapter(this, apiClient);

        layoutManager = new GridLayoutManager(this, adapter.getGridSpanCount());

        layoutManager.setSpanSizeLookup(adapter.createSpanSizeLookup());

        rvTrash.setLayoutManager(layoutManager);

        rvTrash.setHasFixedSize(true);

        rvTrash.setItemViewCacheSize(24);

        rvTrash.setAdapter(adapter);

        rvTrash.addOnScrollListener(new RecyclerView.OnScrollListener() {

            @Override

            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {

                boolean scrolling = newState != RecyclerView.SCROLL_STATE_IDLE;

                TrashAdapter.setScrollPaused(scrolling);

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {

                    adapter.refreshVisibleThumbnails(layoutManager);

                }

            }

        });



        loadTrash();

    }



    @Override

    protected void onResume() {

        super.onResume();

        TrashAdapter.setScrollPaused(false);

        if (adapter != null && layoutManager != null) {

            adapter.refreshVisibleThumbnails(layoutManager);

        }

    }



    private void loadTrash() {

        tvTrashEmpty.setVisibility(View.GONE);

        new Thread(() -> {

            try {

                List<CloudFile> files = apiClient.listTrash();

                runOnUiThread(() -> {

                    adapter.setItems(files);

                    boolean empty = files == null || files.isEmpty();

                    tvTrashEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);

                    if (!empty) {

                        adapter.refreshVisibleThumbnails(layoutManager);

                    }

                });

            } catch (Exception e) {

                runOnUiThread(() ->

                        Toast.makeText(this, getString(R.string.cloud_load_failed, e.getMessage()),

                                Toast.LENGTH_LONG).show());

            }

        }).start();

    }



    @Override

    public void onItemClick(CloudFile file, int position) {

        showTrashActions(file);

    }



    @Override

    public void onItemLongClick(CloudFile file, int position) {

        showTrashActions(file);

    }



    private void showTrashActions(CloudFile file) {

        if (file == null || file.relativePath == null) {

            return;

        }

        new AlertDialog.Builder(this)

                .setTitle(file.name)

                .setItems(new CharSequence[]{

                        getString(R.string.cloud_action_restore),

                        getString(R.string.cloud_action_permanent_delete)

                }, (dialog, which) -> {

                    if (which == 0) {

                        confirmRestore(file);

                    } else {

                        confirmPermanentDelete(file);

                    }

                })

                .show();

    }



    private void confirmRestore(CloudFile file) {

        new AlertDialog.Builder(this)

                .setMessage(getString(R.string.cloud_restore_confirm, file.name))

                .setPositiveButton(R.string.cloud_action_restore, (d, w) -> restoreFile(file))

                .setNegativeButton(android.R.string.cancel, null)

                .show();

    }



    private void confirmPermanentDelete(CloudFile file) {

        new AlertDialog.Builder(this)

                .setMessage(getString(R.string.cloud_permanent_delete_confirm, file.name))

                .setPositiveButton(R.string.cloud_action_permanent_delete, (d, w) -> permanentDelete(file))

                .setNegativeButton(android.R.string.cancel, null)

                .show();

    }



    private void restoreFile(CloudFile file) {

        new Thread(() -> {

            try {

                apiClient.restoreFile(file.relativePath);

                runOnUiThread(() -> {

                    Toast.makeText(this, R.string.cloud_restore_ok, Toast.LENGTH_SHORT).show();

                    loadTrash();

                });

            } catch (Exception e) {

                runOnUiThread(() ->

                        Toast.makeText(this, getString(R.string.cloud_restore_failed, e.getMessage()),

                                Toast.LENGTH_LONG).show());

            }

        }).start();

    }



    private void permanentDelete(CloudFile file) {

        new Thread(() -> {

            try {

                apiClient.permanentDeleteFromTrash(file.relativePath);

                ServerSyncCache.removePath(getApplicationContext(), file.relativePath);
                AppDatabase.getInstance(getApplicationContext()).syncRecordDao()
                        .deleteByRemotePath(file.relativePath);

                runOnUiThread(() -> {

                    Toast.makeText(this, R.string.cloud_permanent_delete_ok, Toast.LENGTH_SHORT).show();

                    loadTrash();

                });

            } catch (Exception e) {

                runOnUiThread(() ->

                        Toast.makeText(this, getString(R.string.cloud_permanent_delete_failed, e.getMessage()),

                                Toast.LENGTH_LONG).show());

            }

        }).start();

    }

}

