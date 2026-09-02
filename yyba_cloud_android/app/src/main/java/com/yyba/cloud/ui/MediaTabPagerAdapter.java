package com.yyba.cloud.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MediaTabPagerAdapter extends FragmentStateAdapter {

    private static final int TAB_COUNT = 4;

    private final String category;

    public MediaTabPagerAdapter(@NonNull FragmentActivity activity, String category) {
        super(activity);
        this.category = category;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        String tabType;
        switch (position) {
            case 1:
                tabType = MediaListAdapter.TAB_CLOUD;
                break;
            case 2:
                tabType = MediaListAdapter.TAB_FAMILY;
                break;
            case 3:
                tabType = MediaListAdapter.TAB_MY_SHARE;
                break;
            default:
                tabType = MediaListAdapter.TAB_PHONE;
                break;
        }
        return MediaTabFragment.newInstance(category, tabType);
    }

    @Override
    public int getItemCount() {
        return TAB_COUNT;
    }
}
