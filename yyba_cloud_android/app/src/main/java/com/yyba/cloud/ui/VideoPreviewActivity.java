package com.yyba.cloud.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.yyba.cloud.R;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.util.CloudDownloadRunner;
import com.yyba.cloud.util.PrefsManager;

import java.util.HashMap;
import java.util.Map;

public class VideoPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_LOCAL_URI = "local_uri";
    public static final String EXTRA_CLOUD_PATH = "cloud_path";
    public static final String EXTRA_OWNER_ID = "owner_id";
    public static final String EXTRA_AUDIO_MODE = "audio_mode";
    /** 云端文件不存在，列表应移除 */
    public static final int RESULT_GHOST_FILE = 1001;

    private MaterialToolbar toolbar;
    private String cloudPath;
    private Long ownerId;
    private boolean audioMode;
    private boolean cloudDownloaded;
    private ExoPlayer player;
    private PlayerView playerView;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_preview);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        this.toolbar = toolbar;
        toolbar.setNavigationOnClickListener(v -> finish());

        playerView = findViewById(R.id.playerView);
        progress = findViewById(R.id.progress);

        String localUri = getIntent().getStringExtra(EXTRA_LOCAL_URI);
        cloudPath = getIntent().getStringExtra(EXTRA_CLOUD_PATH);
        long ownerIdExtra = getIntent().getLongExtra(EXTRA_OWNER_ID, 0L);
        ownerId = ownerIdExtra > 0 ? ownerIdExtra : null;
        audioMode = getIntent().getBooleanExtra(EXTRA_AUDIO_MODE, false);

        if (cloudPath != null && !cloudPath.isEmpty()) {
            toolbar.inflateMenu(R.menu.menu_video_preview);
            toolbar.setOnMenuItemClickListener(this::onToolbarMenuClick);
        }

        if (audioMode) {
            toolbar.setTitle(R.string.preview_audio);
        }

        if (localUri != null && !localUri.isEmpty()) {
            cloudDownloaded = true;
            if (toolbar != null) {
                MenuItem downloadItem = toolbar.getMenu().findItem(R.id.action_download);
                if (downloadItem != null) {
                    downloadItem.setVisible(false);
                }
            }
            playUri(Uri.parse(localUri), null);
            return;
        }

        if (cloudPath == null || cloudPath.isEmpty()) {
            Toast.makeText(this, audioMode ? "无法预览音频" : "无法预览视频", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        playCloudStream();
    }

    private void playCloudStream() {
        progress.setVisibility(View.VISIBLE);
        Long finalOwnerId = ownerId;
        PrefsManager prefs = new PrefsManager(getApplicationContext());
        new Thread(() -> {
            try {
                ApiClient api = new ApiClient(prefs);
                String url = api.buildMediaFileUrl(cloudPath, finalOwnerId);
                String token = prefs.getToken();
                runOnUiThread(() -> playUri(Uri.parse(url), token));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.cloud_file_missing), Toast.LENGTH_LONG).show();
                    finishWithGhostResult();
                });
            }
        }).start();
    }

    private void playUri(Uri uri, String authToken) {
        releasePlayer();
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
        if (authToken != null && !authToken.isEmpty()) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + authToken);
            httpFactory.setDefaultRequestProperties(headers);
        }
        DefaultDataSource.Factory dataSourceFactory =
                new DefaultDataSource.Factory(this, httpFactory);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    progress.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                progress.setVisibility(View.GONE);
                int code = error.errorCode;
                if (code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                        || code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        || code == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
                    Toast.makeText(VideoPreviewActivity.this,
                            getString(R.string.cloud_file_missing),
                            Toast.LENGTH_LONG).show();
                    finishWithGhostResult();
                    return;
                }
                String message;
                if (code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                        || code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    message = getString(R.string.video_format_unsupported);
                } else if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        || code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                    message = getString(R.string.network_error);
                } else {
                    message = getString(R.string.video_play_failed);
                }
                Toast.makeText(VideoPreviewActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
        player.prepare();
        player.play();
    }

    private boolean onToolbarMenuClick(MenuItem item) {
        if (item.getItemId() == R.id.action_download) {
            downloadCloudFile();
            return true;
        }
        return false;
    }

    private void downloadCloudFile() {
        if (cloudPath == null || cloudPath.isEmpty()) {
            return;
        }
        if (cloudDownloaded) {
            Toast.makeText(this, R.string.cloud_download_already_synced, Toast.LENGTH_SHORT).show();
            return;
        }
        String path = cloudPath;
        ApiClient api = new ApiClient(new PrefsManager(getApplicationContext()));
        CloudDownloadRunner.startGallerySync(this, api, path, ownerId,
                (name, saved) -> runOnUiThread(() -> onCloudDownloadSuccess(path)));
    }

    private void onCloudDownloadSuccess(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        cloudDownloaded = true;
        if (toolbar != null) {
            MenuItem downloadItem = toolbar.getMenu().findItem(R.id.action_download);
            if (downloadItem != null) {
                downloadItem.setVisible(false);
            }
            toolbar.setSubtitle(R.string.synced);
        }
    }

    private void finishWithGhostResult() {
        if (cloudPath != null && !cloudPath.isEmpty()) {
            Intent data = new Intent();
            data.putExtra(EXTRA_CLOUD_PATH, cloudPath);
            setResult(RESULT_GHOST_FILE, data);
        }
        finish();
    }

    private void releasePlayer() {
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }
}
