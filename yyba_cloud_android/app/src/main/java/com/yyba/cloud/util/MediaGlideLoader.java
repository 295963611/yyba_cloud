package com.yyba.cloud.util;



import android.content.Context;

import android.net.Uri;

import android.widget.ImageView;



import com.bumptech.glide.Glide;

import com.bumptech.glide.load.engine.DiskCacheStrategy;

import com.bumptech.glide.request.RequestOptions;

import com.yyba.cloud.R;

import com.yyba.cloud.api.AlbumPhotoItem;

import com.yyba.cloud.glide.CloudThumb;

import com.yyba.cloud.sync.MediaScanner;

import com.yyba.cloud.ui.MediaListAdapter;

import com.yyba.cloud.util.DocumentTypes;



/**

 * 相册四 Tab 统一缩略图：本地 MediaStore Uri + 云端鉴权 API，均经 Glide。

 */

public final class MediaGlideLoader {



    private static final int THUMB_SIZE = 320;



    private static final RequestOptions THUMB_OPTIONS = new RequestOptions()

            .centerCrop()

            .override(THUMB_SIZE, THUMB_SIZE)

            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)

            .dontAnimate();



    private static final RequestOptions CLOUD_THUMB_OPTIONS = new RequestOptions()

            .centerCrop()

            .override(THUMB_SIZE, THUMB_SIZE)

            .diskCacheStrategy(DiskCacheStrategy.DATA)

            .dontAnimate();



    private MediaGlideLoader() {

    }



    public static void loadRow(Context context, MediaListAdapter.MediaRow row, ImageView target) {

        if (context == null || row == null || target == null) {

            return;

        }

        if (row.isCloudRow() || row.cloudItem != null) {

            if (row.localThumbUri != null && !row.localThumbUri.isEmpty()) {

                loadLocalUri(context, row.localThumbUri, target);

            } else {

                loadCloud(context, row.cloudItem, target);

            }

        } else {

            loadLocal(context, row.item, target);

        }

    }



    public static void loadLocal(Context context, MediaScanner.MediaItem item, ImageView target) {

        if (context == null || item == null || target == null) {

            return;

        }

        Context ctx = context.getApplicationContext();

        if ("AUDIO".equals(item.mediaType)) {

            clear(ctx, target);

            target.setImageResource(R.drawable.ic_audio);

            return;

        }

        if ("DOCUMENT".equals(item.mediaType)) {

            clear(ctx, target);

            target.setImageResource(R.drawable.ic_document);

            return;

        }

        if (item.uri == null) {

            return;

        }

        loadLocalUri(ctx, item.uri.toString(), target);

    }



    public static void loadLocalUri(Context context, String localUri, ImageView target) {

        if (context == null || localUri == null || localUri.isEmpty() || target == null) {

            return;

        }

        Context ctx = context.getApplicationContext();

        Glide.with(ctx)

                .load(Uri.parse(localUri))

                .apply(THUMB_OPTIONS)

                .into(target);

    }



    public static void loadCloud(Context context, AlbumPhotoItem cloudItem, ImageView target) {

        if (context == null || cloudItem == null || target == null) {

            return;

        }

        Context ctx = context.getApplicationContext();

        String path = cloudItem.relativePath;

        if (path == null) {

            return;

        }

        if (path.startsWith("audio/") || path.startsWith("uploads/audio/")) {

            clear(ctx, target);

            target.setImageResource(R.drawable.ic_audio);

            return;

        }

        if (DocumentTypes.isDocumentPath(path)) {

            clear(ctx, target);

            target.setImageResource(R.drawable.ic_document);

            return;

        }

        int fallback = "VIDEO".equals(fileTypeFromPath(path)) ? R.drawable.ic_video : android.R.drawable.ic_menu_gallery;

        CloudThumb thumb = new CloudThumb(path, cloudItem.ownerIdAsLong());

        Glide.with(ctx)

                .load(thumb)

                .apply(CLOUD_THUMB_OPTIONS)

                .placeholder(fallback)

                .error(fallback)

                .into(target);

    }



    public static void clear(Context context, ImageView target) {

        if (context == null || target == null) {

            return;

        }

        Glide.with(context.getApplicationContext()).clear(target);

    }



    public static void setPaused(Context context, boolean paused) {

        if (context == null) {

            return;

        }

        if (paused) {

            Glide.with(context.getApplicationContext()).pauseRequests();

        } else {

            Glide.with(context.getApplicationContext()).resumeRequests();

        }

    }



    private static String fileTypeFromPath(String path) {

        String lower = path.toLowerCase();

        if (lower.startsWith("videos/")) {

            return "VIDEO";

        }

        if (lower.startsWith("audio/") || lower.startsWith("uploads/audio/")) {

            return "AUDIO";

        }

        return "IMAGE";

    }

}

