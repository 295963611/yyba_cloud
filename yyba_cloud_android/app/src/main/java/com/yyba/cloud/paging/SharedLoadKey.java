package com.yyba.cloud.paging;

import java.util.Objects;

public final class SharedLoadKey {

    public final long beforeTime;
    public final int syncOffset;
    public final boolean syncIndexMode;

    public SharedLoadKey(long beforeTime, int syncOffset, boolean syncIndexMode) {
        this.beforeTime = beforeTime;
        this.syncOffset = syncOffset;
        this.syncIndexMode = syncIndexMode;
    }

    public static SharedLoadKey firstApi() {
        return new SharedLoadKey(System.currentTimeMillis(), 0, false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SharedLoadKey)) {
            return false;
        }
        SharedLoadKey that = (SharedLoadKey) o;
        return beforeTime == that.beforeTime
                && syncOffset == that.syncOffset
                && syncIndexMode == that.syncIndexMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(beforeTime, syncOffset, syncIndexMode);
    }
}
