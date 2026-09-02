package com.yyba.cloud.util;

import com.yyba.cloud.db.SyncRecord;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CloudMediaDownloadHelperBindTest {

    @Test
    public void isSyncedByRecord_trueWhenRemoteFileIdPresent() {
        SyncRecord record = new SyncRecord();
        record.remotePath = "photos/a.jpg";
        record.remoteFileId = 42L;
        assertTrue(SyncStatusHelper.isSyncedByRecord(record));
    }

    @Test
    public void isSyncedByRecord_falseWhenOnlyRemotePath() {
        SyncRecord record = new SyncRecord();
        record.remotePath = "photos/a.jpg";
        assertFalse(SyncStatusHelper.isSyncedByRecord(record));
    }
}
