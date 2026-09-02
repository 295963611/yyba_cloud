package com.yyba.cloud.glide;

import androidx.annotation.Nullable;

import java.util.Objects;

/** Glide 加载云端鉴权缩略图的模型 */
public final class CloudThumb {

    public final String relativePath;
    @Nullable
    public final Long ownerId;

    public CloudThumb(String relativePath, @Nullable Long ownerId) {
        this.relativePath = relativePath;
        this.ownerId = ownerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CloudThumb)) {
            return false;
        }
        CloudThumb that = (CloudThumb) o;
        return Objects.equals(relativePath, that.relativePath)
                && Objects.equals(ownerId, that.ownerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relativePath, ownerId);
    }
}
