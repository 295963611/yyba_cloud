package com.yyba.cloud.ui;



/**

 * 分页列表项的可变 UI 状态，与 PagingData 中的 {@link MediaListAdapter.MediaRow} 分离。

 */

public final class RowOverlayState {



    public boolean synced;

    public boolean syncing;

    public int syncProgress = -1;

    public boolean shared;

    public boolean downloaded;

    public String localThumbUri;

    public String remotePath;



    public static RowOverlayState fromRow(MediaListAdapter.MediaRow row) {

        RowOverlayState state = new RowOverlayState();

        if (row == null) {

            return state;

        }

        state.synced = row.synced;

        state.syncing = row.syncing;

        state.syncProgress = row.syncProgress;

        state.shared = row.shared;

        state.downloaded = row.downloaded;

        state.localThumbUri = row.localThumbUri;

        state.remotePath = row.remotePath;

        return state;

    }



    public void applyTo(MediaListAdapter.MediaRow row) {

        if (row == null) {

            return;

        }

        row.synced = synced;

        row.syncing = syncing;

        row.syncProgress = syncProgress;

        row.shared = shared;

        row.downloaded = downloaded;

        row.localThumbUri = localThumbUri;

        row.remotePath = remotePath;

    }

}

