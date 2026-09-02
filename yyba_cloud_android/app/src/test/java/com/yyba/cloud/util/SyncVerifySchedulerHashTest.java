package com.yyba.cloud.util;

import com.yyba.cloud.api.VerifySyncItem;
import com.yyba.cloud.db.SyncRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class SyncVerifySchedulerHashTest {

    @Test
    public void recordToVerifyItem_includesFileHashWhenIncomplete() {
        SyncRecord record = new SyncRecord();
        record.localUri = "content://media/external/images/1";
        record.remotePath = "photos/a.jpg";
        record.fileSize = 100L;
        record.fileHash = "abc123";
        record.fileName = "a.jpg";
        record.mediaType = "IMAGE";

        VerifySyncItem item = SyncVerifyScheduler.recordToVerifyItem(record);

        assertNotNull(item);
        assertEquals("abc123", item.fileHash);
        assertEquals("photos/a.jpg", item.relativePath);
        assertNull(item.remoteFileId);
    }

    @Test
    public void recordToVerifyItem_usesRemoteFileIdWhenPresent() {
        SyncRecord record = new SyncRecord();
        record.localUri = "content://media/external/images/2";
        record.remoteFileId = 42L;
        record.fileHash = "ignored";

        VerifySyncItem item = SyncVerifyScheduler.recordToVerifyItem(record);

        assertNotNull(item);
        assertEquals(Long.valueOf(42L), item.remoteFileId);
        assertNull(item.fileHash);
    }
}
