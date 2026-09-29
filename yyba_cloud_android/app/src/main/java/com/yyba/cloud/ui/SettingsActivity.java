package com.yyba.cloud.ui;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.yyba.cloud.R;
import com.yyba.cloud.YybaApplication;
import com.yyba.cloud.discovery.ServerConnectCoordinator;
import com.yyba.cloud.sync.SyncWorker;
import com.yyba.cloud.util.EnvironmentIndexGuard;
import com.yyba.cloud.util.PrefsManager;
import com.yyba.cloud.util.ServerAddress;

public class SettingsActivity extends AppCompatActivity {

    private PrefsManager prefs;
    private TextInputEditText etServerHost;
    private TextInputEditText etServerPort;
    private SwitchMaterial switchAutoDiscover;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new PrefsManager(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        etServerHost = findViewById(R.id.etServerHost);
        etServerPort = findViewById(R.id.etServerPort);
        switchAutoDiscover = findViewById(R.id.switchAutoDiscover);
        SwitchMaterial switchWifi = findViewById(R.id.switchWifiOnly);
        SwitchMaterial switchAuto = findViewById(R.id.switchAutoSync);
        MaterialButton btnRediscover = findViewById(R.id.btnRediscover);
        MaterialButton btnSave = findViewById(R.id.btnSave);

        TextView tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setText(getString(R.string.version_format, resolveVersionName()));

        ServerAddress.loadInto(prefs, etServerHost, etServerPort);
        switchAutoDiscover.setChecked(prefs.isAutoDiscover());
        switchWifi.setChecked(prefs.isWifiOnly());
        switchAuto.setChecked(prefs.isAutoSync());

        switchAutoDiscover.setOnCheckedChangeListener((button, checked) -> {
            prefs.setAutoDiscover(checked);
            btnRediscover.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        btnRediscover.setVisibility(prefs.isAutoDiscover() ? View.VISIBLE : View.GONE);

        btnRediscover.setOnClickListener(v -> {
            btnRediscover.setEnabled(false);
            ServerConnectCoordinator coordinator =
                    ((YybaApplication) getApplication()).getConnectCoordinator();
            coordinator.connectManualRediscover(this, result -> {
                btnRediscover.setEnabled(true);
                ServerAddress.loadInto(prefs, etServerHost, etServerPort);
                if (result.url != null && !result.url.isEmpty()) {
                    Toast.makeText(this, prefs.getServerDisplay(), Toast.LENGTH_SHORT).show();
                } else if (result.servers.isEmpty()) {
                    Toast.makeText(this, R.string.discover_none, Toast.LENGTH_SHORT).show();
                } else if (result.multiServerBackground) {
                    Toast.makeText(this, R.string.discover_multi_go_settings, Toast.LENGTH_LONG).show();
                }
            });
        });

        btnSave.setOnClickListener(v -> {
            if (!ServerAddress.saveFrom(prefs, etServerHost, etServerPort)) {
                String hostInput = etServerHost.getText() != null ? etServerHost.getText().toString() : "";
                if (ServerAddress.parse(hostInput).host.isEmpty()) {
                    Toast.makeText(this, "请填写服务器 IP", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.discovery_endpoint_save_failed, Toast.LENGTH_LONG).show();
                }
                return;
            }
            prefs.setAutoDiscover(switchAutoDiscover.isChecked());
            prefs.setWifiOnly(switchWifi.isChecked());
            prefs.setAutoSync(switchAuto.isChecked());
            if (switchAuto.isChecked()) {
                SyncWorker.schedule(this);
            } else {
                SyncWorker.cancel(this);
            }
            if (EnvironmentIndexGuard.applyIfChanged(this, prefs)) {
                Toast.makeText(this, R.string.env_switch_loading, Toast.LENGTH_SHORT).show();
            }
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private String resolveVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0.0";
        }
    }
}
