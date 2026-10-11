package com.yyba.cloud.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.yyba.cloud.R;
import com.yyba.cloud.YybaApplication;
import com.yyba.cloud.discovery.ServerConnectCoordinator;
import com.yyba.cloud.sync.SyncEngine;
import com.yyba.cloud.sync.SyncWorker;
import com.yyba.cloud.util.EnvironmentIndexGuard;
import com.yyba.cloud.util.PrefsManager;
import com.yyba.cloud.util.ServerSyncCache;

public class MainActivity extends AppCompatActivity {

    private static final String TAG_HOME = "home";
    private static final String TAG_CLOUD = "cloud";

    private PrefsManager prefs;
    private HomeFragment homeFragment;
    private CloudFragment cloudFragment;
    private boolean showingCloud;
    private OnBackPressedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new PrefsManager(this);
        if (prefs.getToken() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        if (EnvironmentIndexGuard.applyIfChanged(getApplicationContext(), prefs)) {
            Toast.makeText(this, R.string.env_switch_loading, Toast.LENGTH_SHORT).show();
        }
        if (prefs.isAutoSync()) {
            SyncWorker.schedule(this);
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);

        homeFragment = (HomeFragment) getSupportFragmentManager().findFragmentByTag(TAG_HOME);
        cloudFragment = (CloudFragment) getSupportFragmentManager().findFragmentByTag(TAG_CLOUD);

        if (savedInstanceState == null) {
            if (homeFragment == null) {
                homeFragment = new HomeFragment();
            }
            if (cloudFragment == null) {
                cloudFragment = new CloudFragment();
            }
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragmentContainer, cloudFragment, TAG_CLOUD)
                    .hide(cloudFragment)
                    .add(R.id.fragmentContainer, homeFragment, TAG_HOME)
                    .commitNow();
            toolbar.setTitle(R.string.nav_home);
            bottomNav.setSelectedItemId(R.id.nav_home);
        }

        bottomNav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_cloud) {
                switchTab(toolbar, true);
            } else {
                switchTab(toolbar, false);
            }
            return true;
        });

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                Log.d("MainActivity", "handleOnBackPressed: showingCloud=" + showingCloud
                        + ", cloudFragment=" + (cloudFragment != null ? "not null" : "null")
                        + ", canGoBack=" + (cloudFragment != null && cloudFragment.canGoBack()));
                if (showingCloud && cloudFragment != null && cloudFragment.canGoBack()) {
                    cloudFragment.goBack();
                } else if (showingCloud) {
                    bottomNav.setSelectedItemId(R.id.nav_home);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        updateHomeServerUrl();
    }

    private void updateHomeServerUrl() {
        ServerConnectCoordinator coordinator =
                ((YybaApplication) getApplication()).getConnectCoordinator();
        String url = coordinator.getResolvedUrl();
        if (homeFragment == null) {
            homeFragment = (HomeFragment) getSupportFragmentManager().findFragmentByTag(TAG_HOME);
        }
        if (homeFragment != null && url != null && !url.isEmpty()) {
            homeFragment.updateServerUrl(url);
        }
    }

    private void switchTab(MaterialToolbar toolbar, boolean cloud) {
        showingCloud = cloud;
        if (cloud) {
            if (cloudFragment == null) {
                cloudFragment = (CloudFragment) getSupportFragmentManager().findFragmentByTag(TAG_CLOUD);
            }
            if (cloudFragment == null) {
                cloudFragment = new CloudFragment();
            }
            toolbar.setTitle(R.string.nav_cloud);
            showOnly(cloudFragment, TAG_CLOUD);
        } else {
            if (homeFragment == null) {
                homeFragment = (HomeFragment) getSupportFragmentManager().findFragmentByTag(TAG_HOME);
            }
            if (homeFragment == null) {
                homeFragment = new HomeFragment();
            }
            toolbar.setTitle(R.string.nav_home);
            showOnly(homeFragment, TAG_HOME);
        }
        if (backCallback != null) {
            backCallback.setEnabled(showingCloud);
        }
    }

    private void showOnly(Fragment target, String tag) {
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        Fragment home = getSupportFragmentManager().findFragmentByTag(TAG_HOME);
        Fragment cloud = getSupportFragmentManager().findFragmentByTag(TAG_CLOUD);
        if (home != null && home.isAdded()) {
            tx.hide(home);
        }
        if (cloud != null && cloud.isAdded()) {
            tx.hide(cloud);
        }
        Fragment existing = getSupportFragmentManager().findFragmentByTag(tag);
        if (existing != null && existing.isAdded()) {
            tx.show(existing);
        } else {
            tx.add(R.id.fragmentContainer, target, tag);
        }
        tx.commit();
    }

    public void logout() {
        SyncEngine.haltBackgroundSync(this);
        SyncWorker.cancel(this);
        ServerSyncCache.clearAllSync(this);
        prefs.clear();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
