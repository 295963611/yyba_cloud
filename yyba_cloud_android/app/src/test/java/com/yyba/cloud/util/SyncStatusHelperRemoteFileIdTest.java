package com.yyba.cloud.util;

import com.yyba.cloud.db.SyncRecord;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SyncStatusHelperRemoteFileIdTest {

    @Test
    public void isSyncedByRecord_requiresRemoteFileId() {
        SyncRecord record = new SyncRecord();
        record.remoteFileId = 100L;
        assertTrue(SyncStatusHelper.isSyncedByRecord(record));

        record.remoteFileId = null;
        assertFalse(SyncStatusHelper.isSyncedByRecord(record));

        assertFalse(SyncStatusHelper.isSyncedByRecord(null));
    }

    @Test
    public void isDuplicateOfBoundCloudFile_usesRemoteFileId() {
        SyncRecord bound = new SyncRecord();
        bound.remoteFileId = 1L;
        bound.fileName = "a.jpg";
        bound.fileSize = 1024L;

        java.util.Map<String, SyncRecord> index = new java.util.HashMap<>();
        index.put("content://media/1", bound);

        com.yyba.cloud.sync.MediaScanner.MediaItem camera = new com.yyba.cloud.sync.MediaScanner.MediaItem();
        camera.fileName = "a.jpg";
        camera.fileSize = 1024L;

        assertTrue(SyncStatusHelper.isDuplicateOfBoundCloudFile(camera, null, index));
    }
}
