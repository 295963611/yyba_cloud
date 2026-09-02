package com.yyba.cloud.util;

import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.sync.MediaScanner;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** SyncStatusHelper 纯 JVM 可测逻辑 */
public class SyncStatusHelperTest {

    @Test
    public void buildCanonicalUriByRemotePath_mapsRemoteToLocalUri() {
        Map<String, SyncRecord> index = new HashMap<>();
        SyncRecord record = new SyncRecord();
        record.localUri = "content://media/1";
        record.remotePath = "photos/2023/04/a.jpg";
        index.put(record.localUri, record);

        Map<String, String> canonical = SyncStatusHelper.buildCanonicalUriByRemotePath(index);
        assertEquals("content://media/1", canonical.get("photos/2023/04/a.jpg"));
    }

    @Test
    public void isDuplicateOfBoundCloudFile_detectsSameNameAndSize() {
        Map<String, SyncRecord> index = new HashMap<>();
        SyncRecord record = new SyncRecord();
        record.remotePath = "photos/2023/04/a.jpg";
        record.fileName = "a.jpg";
        record.fileSize = 1024L;
        index.put("content://media/1", record);

        MediaScanner.MediaItem camera = new MediaScanner.MediaItem();
        camera.fileName = "a.jpg";
        camera.fileSize = 1024L;

        Set<String> serverPaths = new HashSet<>(Collections.singletonList("photos/2023/04/a.jpg"));
        assertTrue(SyncStatusHelper.isDuplicateOfBoundCloudFile(
                camera, null, index, serverPaths));
    }

    @Test
    public void isDuplicateOfBoundCloudFile_ignoresWhenItemHasOwnRecord() {
        Map<String, SyncRecord> index = new HashMap<>();
        SyncRecord record = new SyncRecord();
        record.remotePath = "photos/2023/04/a.jpg";
        record.fileName = "a.jpg";
        record.fileSize = 1024L;
        index.put("content://media/1", record);

        MediaScanner.MediaItem item = new MediaScanner.MediaItem();
        item.fileName = "a.jpg";
        item.fileSize = 1024L;
        SyncRecord own = new SyncRecord();

        assertFalse(SyncStatusHelper.isDuplicateOfBoundCloudFile(
                item, own, index, Collections.singleton("photos/2023/04/a.jpg")));
    }

    @Test
    public void isRecordSyncedOnServer_requiresPathInIndex() {
        SyncRecord record = new SyncRecord();
        record.remotePath = "photos/2025/05/photo.jpg";
        Set<String> serverPaths = new HashSet<>(Collections.singletonList("photos/2025/05/photo.jpg"));
        assertTrue(SyncStatusHelper.isRecordSyncedOnServer(record, serverPaths));

        assertFalse(SyncStatusHelper.isRecordSyncedOnServer(record, Collections.emptySet()));
        assertFalse(SyncStatusHelper.isRecordSyncedOnServer(record,
                new HashSet<>(Collections.singletonList("photos/2025/05/other.jpg"))));
        assertFalse(SyncStatusHelper.isRecordSyncedOnServer(null, serverPaths));
    }

    @Test
    public void isRecordSyncedOnServer_modifiedAtAloneIsNotSynced() {
        SyncRecord record = new SyncRecord();
        record.remotePath = "photos/2025/05/guess.jpg";
        record.modifiedAt = 2000L;
        Set<String> serverPaths = new HashSet<>(Collections.singletonList("photos/2025/05/real.jpg"));
        assertFalse(SyncStatusHelper.isRecordSyncedOnServer(record, serverPaths));
    }

    @Test
    public void lookupCloudPath_findsPathByNameAndSize() {
        MediaScanner.MediaItem item = new MediaScanner.MediaItem();
        item.fileName = "photo.jpg";
        item.fileSize = 2048L;

        Map<String, String> cloudByNameSize = new HashMap<>();
        cloudByNameSize.put("photo.jpg|2048", "photos/2025/05/photo.jpg");
        Set<String> serverPaths = new HashSet<>(Collections.singletonList("photos/2025/05/photo.jpg"));

        assertEquals("photos/2025/05/photo.jpg",
                SyncStatusHelper.lookupCloudPath(item, serverPaths, cloudByNameSize));
    }

    @Test
    public void isDownloadedFast_emptyIndexIsNotDownloaded() {
        com.yyba.cloud.api.SyncIndexItem item = new com.yyba.cloud.api.SyncIndexItem();
        item.relativePath = "photos/2025/05/photo.jpg";
        item.fileSize = 1024L;
        assertFalse(SyncStatusHelper.isDownloadedFast(null, null, item));
        assertFalse(SyncStatusHelper.isDownloadedFast(null, Collections.emptyMap(), item));
    }
}
