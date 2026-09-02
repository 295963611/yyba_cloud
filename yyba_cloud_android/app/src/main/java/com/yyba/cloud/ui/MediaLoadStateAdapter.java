package com.yyba.cloud.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.paging.LoadState;
import androidx.paging.LoadStateAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.yyba.cloud.R;

public final class MediaLoadStateAdapter extends LoadStateAdapter<MediaLoadStateAdapter.FooterHolder> {

    @NonNull
    @Override
    public FooterHolder onCreateViewHolder(@NonNull ViewGroup parent, @NonNull LoadState loadState) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_loading_footer, parent, false);
        return new FooterHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FooterHolder holder, @NonNull LoadState loadState) {
    }

    static class FooterHolder extends RecyclerView.ViewHolder {
        FooterHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
