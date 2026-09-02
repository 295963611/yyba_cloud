package com.yyba.cloud.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.yyba.cloud.R;
import com.yyba.cloud.util.SharedMediaPrefetchCache;

public class MediaListActivity extends AppCompatActivity {

    public static final String EXTRA_CATEGORY = "category";
    public static final String EXTRA_TITLE = "title";

    private String category;
    private String baseTitle;
    private ViewPager2 viewPager;
    private MaterialToolbar toolbar;
    private View selectionBottomBar;
    private MaterialButton btnSelectionShare;
    private MaterialButton btnSelectionUnshare;
    private MaterialButton btnSelectionDownload;
    private MaterialButton btnSelectionDelete;

    private static final int BAR_NONE = 0;
    private static final int BAR_PHONE = 1;
    private static final int BAR_CLOUD = 2;
    private static final int BAR_MY_SHARE = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_list);

        category = getIntent().getStringExtra(EXTRA_CATEGORY);
        baseTitle = getIntent().getStringExtra(EXTRA_TITLE);
        if (category != null) {
            MediaTabFragment.prewarmBeforeOpen(this, category);
            SharedMediaPrefetchCache.prewarm(this, category);
        }

        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(baseTitle);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                MediaTabFragment fragment = findFragmentInSelectionMode();
                if (fragment != null) {
                    fragment.clearSelection();
                    return;
                }
                finish();
            }
        });
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        selectionBottomBar = findViewById(R.id.selectionBottomBar);
        btnSelectionShare = findViewById(R.id.btnSelectionShare);
        btnSelectionUnshare = findViewById(R.id.btnSelectionUnshare);
        btnSelectionDownload = findViewById(R.id.btnSelectionDownload);
        btnSelectionDelete = findViewById(R.id.btnSelectionDelete);
        btnSelectionShare.setOnClickListener(v -> {
            MediaTabFragment fragment = getCurrentFragment();
            if (fragment != null) {
                fragment.shareSelected();
            }
        });
        btnSelectionUnshare.setOnClickListener(v -> {
            MediaTabFragment fragment = getCurrentFragment();
            if (fragment != null) {
                fragment.unshareSelected();
            }
        });
        btnSelectionDownload.setOnClickListener(v -> {
            MediaTabFragment fragment = getCurrentFragment();
            if (fragment != null) {
                fragment.downloadSelected();
            }
        });
        btnSelectionDelete.setOnClickListener(v -> {
            MediaTabFragment fragment = getCurrentFragment();
            if (fragment != null) {
                fragment.deleteSelected();
            }
        });

        TabLayout tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);
        MediaTabPagerAdapter pagerAdapter = new MediaTabPagerAdapter(this, category);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(2);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 1:
                    tab.setText(R.string.tab_cloud);
                    break;
                case 2:
                    tab.setText(R.string.tab_family);
                    break;
                case 3:
                    tab.setText(R.string.tab_my_share);
                    break;
                default:
                    tab.setText(R.string.tab_phone);
                    break;
            }
        }).attach();

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                MediaTabFragment inSelection = findFragmentInSelectionMode();
                if (inSelection != null) {
                    inSelection.clearSelection();
                }
                invalidateOptionsMenu();
                notifyTabVisible(position);
            }
        });

        notifyTabVisible(0);
        viewPager.post(() -> notifyTabVisible(viewPager.getCurrentItem()));
    }

    public int getCurrentTabPosition() {
        return viewPager != null ? viewPager.getCurrentItem() : 0;
    }

    public int getTabPositionForType(String tabType) {
        if (MediaListAdapter.TAB_CLOUD.equals(tabType)) {
            return 1;
        }
        if (MediaListAdapter.TAB_FAMILY.equals(tabType)) {
            return 2;
        }
        if (MediaListAdapter.TAB_MY_SHARE.equals(tabType)) {
            return 3;
        }
        return 0;
    }

    private void notifyTabVisible(int position) {
        if (viewPager == null) {
            return;
        }
        viewPager.post(() -> {
            MediaTabFragment fragment = findTabFragment(position);
            if (fragment != null) {
                fragment.loadWhenVisible();
            }
        });
    }

    private MediaTabFragment findTabFragment(int position) {
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
                .findFragmentByTag("f" + position);
        if (fragment instanceof MediaTabFragment) {
            return (MediaTabFragment) fragment;
        }
        String tabType = tabTypeForPosition(position);
        for (androidx.fragment.app.Fragment candidate : getSupportFragmentManager().getFragments()) {
            if (candidate instanceof MediaTabFragment tab && tabType.equals(tab.getTabType())) {
                return tab;
            }
        }
        return null;
    }

    private static String tabTypeForPosition(int position) {
        switch (position) {
            case 1:
                return MediaListAdapter.TAB_CLOUD;
            case 2:
                return MediaListAdapter.TAB_FAMILY;
            case 3:
                return MediaListAdapter.TAB_MY_SHARE;
            default:
                return MediaListAdapter.TAB_PHONE;
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    @Override
    @Deprecated
    public void onBackPressed() {
        getOnBackPressedDispatcher().onBackPressed();
    }

    public void updateSelectionTitle(int count) {
        MediaTabFragment fragment = getCurrentFragment();
        int barMode = BAR_NONE;
        if (fragment != null && count > 0) {
            if (MediaListAdapter.TAB_PHONE.equals(fragment.getTabType())) {
                barMode = BAR_PHONE;
            } else if (MediaListAdapter.TAB_CLOUD.equals(fragment.getTabType())) {
                barMode = BAR_CLOUD;
            } else if (MediaListAdapter.TAB_MY_SHARE.equals(fragment.getTabType())) {
                barMode = BAR_MY_SHARE;
            }
        }
        if (count > 0) {
            toolbar.setTitle(getString(R.string.select_items, count));
        } else {
            toolbar.setTitle(baseTitle);
        }
        updateSelectionBottomBar(barMode, fragment);
        invalidateOptionsMenu();
    }

    private void updateSelectionBottomBar(int barMode, MediaTabFragment fragment) {
        if (selectionBottomBar == null) {
            return;
        }
        if (barMode == BAR_NONE || fragment == null) {
            selectionBottomBar.setVisibility(View.GONE);
            return;
        }
        selectionBottomBar.setVisibility(View.VISIBLE);
        int unshared = fragment.getSelectedUnsharedShareCount();
        int shared = fragment.getSelectedSharedShareCount();

        if (unshared > 0) {
            btnSelectionShare.setVisibility(View.VISIBLE);
            btnSelectionShare.setText(getString(R.string.share_with_count, unshared));
            btnSelectionShare.setEnabled(true);
        } else {
            btnSelectionShare.setVisibility(View.GONE);
        }

        if (shared > 0) {
            btnSelectionUnshare.setVisibility(View.VISIBLE);
            btnSelectionUnshare.setText(getString(R.string.unshare_with_count, shared));
            btnSelectionUnshare.setEnabled(true);
        } else {
            btnSelectionUnshare.setVisibility(View.GONE);
        }

        if (barMode == BAR_PHONE) {
            btnSelectionDownload.setVisibility(View.GONE);
            btnSelectionDelete.setVisibility(View.VISIBLE);
            int deletable = fragment.getSelectedDeletableCount();
            btnSelectionDelete.setEnabled(deletable > 0);
        } else if (barMode == BAR_CLOUD) {
            btnSelectionDownload.setVisibility(View.VISIBLE);
            btnSelectionDelete.setVisibility(View.VISIBLE);
            int downloadable = fragment.getSelectedDownloadCount();
            int deletable = fragment.getSelectedDeletableCount();
            btnSelectionDownload.setEnabled(downloadable > 0);
            btnSelectionDelete.setEnabled(deletable > 0);
        } else if (barMode == BAR_MY_SHARE) {
            btnSelectionShare.setVisibility(View.GONE);
            btnSelectionDownload.setVisibility(View.GONE);
            btnSelectionDelete.setVisibility(View.GONE);
            int shareable = fragment.getSelectedShareableCount();
            if (shareable > 0) {
                btnSelectionUnshare.setVisibility(View.VISIBLE);
                btnSelectionUnshare.setText(getString(R.string.unshare_with_count, shareable));
                btnSelectionUnshare.setEnabled(true);
            } else {
                btnSelectionUnshare.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MediaTabFragment fragment = getCurrentFragment();
        if (fragment == null) return super.onCreateOptionsMenu(menu);
        menu.clear();
        if (fragment.isSelectionMode()
                && (MediaListAdapter.TAB_PHONE.equals(fragment.getTabType())
                || MediaListAdapter.TAB_CLOUD.equals(fragment.getTabType())
                || MediaListAdapter.TAB_MY_SHARE.equals(fragment.getTabType()))) {
            return true;
        }
        String tabType = fragment.getTabType();
        if (MediaListAdapter.TAB_PHONE.equals(tabType)) {
            getMenuInflater().inflate(R.menu.menu_media_phone, menu);
        } else if (MediaListAdapter.TAB_CLOUD.equals(tabType)) {
            getMenuInflater().inflate(R.menu.menu_media_cloud, menu);
        } else if (MediaListAdapter.TAB_MY_SHARE.equals(tabType)) {
            getMenuInflater().inflate(R.menu.menu_media_my_share, menu);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        MediaTabFragment fragment = getCurrentFragment();
        if (fragment == null) return super.onOptionsItemSelected(item);

        int id = item.getItemId();
        if (id == R.id.action_delete_selected) {
            fragment.deleteSelected();
            return true;
        }
        if (id == R.id.action_clean_backed_up) {
            fragment.cleanBackedUp();
            return true;
        }
        if (id == R.id.action_download_selected) {
            fragment.downloadSelected();
            return true;
        }
        if (id == R.id.action_share_all) {
            confirmShareAll(true);
            return true;
        }
        if (id == R.id.action_unshare_all) {
            confirmShareAll(false);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmShareAll(boolean share) {
        String typeLabel;
        if ("VIDEO".equals(category)) {
            typeLabel = getString(R.string.category_video);
        } else if ("AUDIO".equals(category)) {
            typeLabel = getString(R.string.category_audio);
        } else {
            typeLabel = getString(R.string.category_album);
        }
        new AlertDialog.Builder(this)
                .setMessage(getString(share ? R.string.confirm_share_all : R.string.confirm_unshare_all, typeLabel))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    MediaTabFragment fragment = getCurrentFragment();
                    if (fragment != null) {
                        if (share) fragment.shareAll();
                        else fragment.unshareAll();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private MediaTabFragment getCurrentFragment() {
        if (viewPager == null) {
            return null;
        }
        return findTabFragment(viewPager.getCurrentItem());
    }

    private MediaTabFragment findFragmentInSelectionMode() {
        for (int i = 0; i < 4; i++) {
            androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
                    .findFragmentByTag("f" + i);
            if (fragment instanceof MediaTabFragment) {
                MediaTabFragment tab = (MediaTabFragment) fragment;
                if (tab.isSelectionMode()) {
                    return tab;
                }
            }
        }
        return null;
    }

    /** 分享状态变更后仅刷新指定 Tab */
    public void reloadTab(String tabType) {
        MediaTabFragment tab = findTabFragment(getTabPositionForType(tabType));
        if (tab != null) {
            tab.forceReload();
        }
    }
}
