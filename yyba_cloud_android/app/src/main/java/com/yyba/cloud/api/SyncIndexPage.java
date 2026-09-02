package com.yyba.cloud.api;

import java.util.List;

public class SyncIndexPage {
    public List<SyncIndexItem> items;
    public List<String> removedPaths;
    public long serverTime;
    public boolean fullSnapshot;
    public boolean hasMore;
    public String nextCursor;
}
