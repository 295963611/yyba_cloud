package com.yyba.cloud.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.yyba.cloud.R;
import com.yyba.cloud.YybaApplication;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.discovery.ServerConnectCoordinator;
import com.yyba.cloud.discovery.ServerDiscoveryManager;
import com.yyba.cloud.sync.SyncEngine;
import com.yyba.cloud.sync.SyncWorker;
import com.yyba.cloud.util.EnvironmentIndexGuard;
import com.yyba.cloud.util.PrefsManager;
import com.yyba.cloud.util.ServerAddress;
import com.yyba.cloud.util.SessionManager;

import java.util.concurrent.atomic.AtomicBoolean;

public class LoginActivity extends AppCompatActivity {

    private PrefsManager prefs;
    private TextInputEditText etServerHost;
    private TextInputEditText etServerPort;
    private TextView tvDiscoverStatus;
    private MaterialCardView loginCard;
    private MaterialButton btnLogin;
    private View loginLoadingOverlay;
    private TextView tvLoginLoading;
    private boolean pendingGoMain;
    /** 已进入主页后忽略迟到的 health/mDNS 回调，避免在相册等页面误弹登录 Toast */
    private volatile boolean exitedToMain;
    private final AtomicBoolean autoEnterInProgress = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new PrefsManager(this);
        pendingGoMain = prefs.getToken() != null;
        SyncEngine.haltBackgroundSync(this);

        setContentView(R.layout.activity_login);

        etServerHost = findViewById(R.id.etServerHost);
        etServerPort = findViewById(R.id.etServerPort);
        TextInputEditText etUsername = findViewById(R.id.etUsername);
        TextInputEditText etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvDiscoverStatus = findViewById(R.id.tvDiscoverStatus);
        loginCard = findViewById(R.id.loginCard);
        loginLoadingOverlay = findViewById(R.id.loginLoadingOverlay);
        tvLoginLoading = findViewById(R.id.tvLoginLoading);

        if (pendingGoMain) {
            loginCard.setVisibility(View.GONE);
            showLoading(R.string.login_entering);
        }

        ServerAddress.loadInto(prefs, etServerHost, etServerPort);

        btnLogin.setOnClickListener(v -> {
            String username = etUsername.getText() != null ? etUsername.getText().toString().trim() : "";
            String password = etPassword.getText() != null ? etPassword.getText().toString() : "";
            String hostInput = etServerHost.getText() != null ? etServerHost.getText().toString() : "";
            if (ServerAddress.parse(hostInput).host.isEmpty() || username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!ServerAddress.saveFrom(prefs, etServerHost, etServerPort)) {
                Toast.makeText(this, R.string.discovery_endpoint_save_failed, Toast.LENGTH_LONG).show();
                return;
            }
            if (!prefs.isSecureStorageAvailable()) {
                Toast.makeText(this, R.string.secure_storage_write_failed, Toast.LENGTH_LONG).show();
                return;
            }
            btnLogin.setEnabled(false);
            showLoading(R.string.login_logging_in);
            new Thread(() -> {
                try {
                    ApiClient client = new ApiClient(prefs);
                    ApiClient.LoginResult result = client.login(username, password);
                    prefs.setToken(result.token);
                    prefs.setUsername(result.username);
                    prefs.setRole(result.role);
                    if (EnvironmentIndexGuard.applyIfChanged(getApplicationContext(), prefs)) {
                        runOnUiThread(() -> {
                            if (!isConnectUiActive()) {
                                return;
                            }
                            Toast.makeText(this, R.string.env_switch_loading, Toast.LENGTH_SHORT).show();
                        });
                    }
                    runOnUiThread(() -> {
                        if (!isConnectUiActive()) {
                            return;
                        }
                        if (prefs.isAutoSync()) {
                            SyncWorker.schedule(this);
                        }
                        goMain();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (!isConnectUiActive()) {
                            return;
                        }
                        hideLoading();
                        btnLogin.setEnabled(true);
                        Toast.makeText(this, "登录失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });

        startConnect();
    }

    private boolean isConnectUiActive() {
        return !exitedToMain && !isFinishing() && !isDestroyed();
    }

    private boolean shouldHandleAutoEnter() {
        return pendingGoMain && isConnectUiActive();
    }

    private void showLoading(int messageRes) {
        if (loginLoadingOverlay != null) {
            loginLoadingOverlay.setVisibility(View.VISIBLE);
        }
        if (tvLoginLoading != null) {
            tvLoginLoading.setText(messageRes);
        }
    }

    private void hideLoading() {
        if (loginLoadingOverlay != null) {
            loginLoadingOverlay.setVisibility(View.GONE);
        }
    }

    private void startConnect() {
        if (!prefs.isAutoDiscover() && prefs.getServerUrl().isEmpty()) {
            tvDiscoverStatus.setVisibility(View.GONE);
            if (pendingGoMain) {
                showLoginRequired(R.string.server_unreachable_check_network);
            }
            return;
        }

        if (!prefs.isAutoDiscover()) {
            tvDiscoverStatus.setVisibility(View.GONE);
            if (pendingGoMain) {
                // health 仅 2s 超时，API 可用时仍应自动进主页；勿在 health 失败时立刻弹 unreachable
                tryAutoEnterFromToken();
            }
            return;
        }

        tvDiscoverStatus.setVisibility(View.GONE);

        ServerConnectCoordinator coordinator =
                ((YybaApplication) getApplication()).getConnectCoordinator();
        coordinator.connectOnColdStart(this, this::handleConnectResult);
    }

    private void handleConnectResult(ServerConnectCoordinator.ConnectResult result) {
        if (!isConnectUiActive()) {
            return;
        }

        if (result.url != null && !result.url.isEmpty()) {
            ServerAddress.loadFromUrl(etServerHost, etServerPort, result.url);
        }

        if (result.mdnsPerformed) {
            tvDiscoverStatus.setVisibility(View.VISIBLE);
        }
        if (result.multiServerBackground) {
            tvDiscoverStatus.setText(R.string.discover_multi_go_settings);
            tvDiscoverStatus.setVisibility(View.VISIBLE);
        } else if (result.servers.isEmpty() && result.savedAddressUnreachable) {
            tvDiscoverStatus.setText(R.string.discover_none);
            tvDiscoverStatus.setVisibility(View.VISIBLE);
        } else if (result.healthOk) {
            tvDiscoverStatus.setVisibility(View.GONE);
        } else if (result.mdnsPerformed && result.servers.isEmpty()) {
            tvDiscoverStatus.setText(R.string.discover_none);
            tvDiscoverStatus.setVisibility(View.VISIBLE);
        } else {
            tvDiscoverStatus.setVisibility(View.GONE);
        }

        if (shouldHandleAutoEnter()) {
            tryAutoEnterFromToken();
        }
    }

    /** 用 token 校验决定是否自动进主页；health/mDNS 失败但 API 可达时不误报 unreachable */
    private void tryAutoEnterFromToken() {
        if (!shouldHandleAutoEnter()) {
            return;
        }
        if (!autoEnterInProgress.compareAndSet(false, true)) {
            return;
        }
        showLoading(R.string.login_entering);
        new Thread(() -> {
            try {
                ApiClient.TokenValidation validation = new ApiClient(prefs).validateToken();
                runOnUiThread(() -> {
                    if (!shouldHandleAutoEnter()) {
                        return;
                    }
                    if (validation == ApiClient.TokenValidation.VALID) {
                        goMain();
                        return;
                    }
                    if (validation == ApiClient.TokenValidation.EXPIRED) {
                        SessionManager.invalidateOnAuthFailure(this, prefs);
                        showLoginRequired(R.string.login_expired);
                        return;
                    }
                    showLoginRequired(R.string.server_unreachable_check_network);
                });
            } finally {
                autoEnterInProgress.set(false);
            }
        }, "login-auto-enter").start();
    }

    private void showLoginRequired(int messageRes) {
        if (exitedToMain || isFinishing() || isDestroyed()) {
            return;
        }
        pendingGoMain = false;
        hideLoading();
        loginCard.setVisibility(View.VISIBLE);
        Toast.makeText(getApplicationContext(), messageRes, Toast.LENGTH_LONG).show();
    }

    private void goMain() {
        if (exitedToMain) {
            return;
        }
        exitedToMain = true;
        pendingGoMain = false;
        hideLoading();
        if (prefs.isAutoSync()) {
            SyncWorker.schedule(this);
        }
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        exitedToMain = true;
        ServerDiscoveryManager manager = ((YybaApplication) getApplication()).getDiscoveryManager();
        manager.stop();
        super.onDestroy();
    }
}
