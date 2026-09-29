package com.yyba.cloud.service;



import com.yyba.cloud.config.SqliteWriteGuard;

import com.yyba.cloud.dto.VerifySyncItemDto;

import com.yyba.cloud.dto.VerifySyncRequest;

import com.yyba.cloud.dto.VerifySyncResponse;
import com.yyba.cloud.dto.SyncRecordDto;

import com.yyba.cloud.entity.CloudFile;

import com.yyba.cloud.entity.User;

import com.yyba.cloud.mapper.CloudFileMapper;

import com.yyba.cloud.mapper.UserMapper;

import com.yyba.cloud.util.TimeUtil;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.ExtendWith;

import org.junit.jupiter.api.io.TempDir;

import org.mockito.Mock;

import org.mockito.junit.jupiter.MockitoExtension;



import java.nio.file.Files;

import java.nio.file.Path;

import java.util.List;



import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)

class FileServiceVerifySyncTest {



    @Mock

    private CloudFileMapper cloudFileMapper;

    @Mock

    private UserMapper userMapper;

    @Mock

    private ConfigService configService;

    @Mock

    private OperationLogService operationLogService;

    @Mock

    private ThumbnailService thumbnailService;

    @Mock

    private DiskIndexService diskIndexService;

    @Mock

    private SqliteWriteGuard sqliteWriteGuard;



    private FileService fileService;



    @TempDir

    Path tempDir;



    private User user;



    @BeforeEach

    void setUp() {

        fileService = new FileService(

                cloudFileMapper, userMapper, configService, operationLogService,

                thumbnailService, diskIndexService, sqliteWriteGuard);

        user = new User();

        user.setId(1L);

        user.setUsername("test");

        when(configService.getStorageRoot()).thenReturn(tempDir);

    }



    @Test

    void verifySync_invalidWhenDiskMissing() throws Exception {

        CloudFile file = activeFile(10L, "photos/a.jpg");

        when(cloudFileMapper.selectById(10L)).thenReturn(file);



        VerifySyncRequest request = new VerifySyncRequest();

        request.setFileIds(List.of(10L));

        VerifySyncResponse response = fileService.verifySync(user, request);



        assertEquals(List.of(10L), response.getInvalidIds());

        assertTrue(response.getValidItems().isEmpty());

        assertTrue(response.getRebuiltRecords().isEmpty());

    }



    @Test

    void verifySync_validWhenDiskExists() throws Exception {

        Path userRoot = tempDir.resolve("test");

        Files.createDirectories(userRoot.resolve("photos"));

        Path photo = userRoot.resolve("photos/a.jpg");

        Files.writeString(photo, "data");



        CloudFile file = activeFile(11L, "photos/a.jpg");

        when(cloudFileMapper.selectById(11L)).thenReturn(file);



        VerifySyncRequest request = new VerifySyncRequest();

        request.setFileIds(List.of(11L));

        VerifySyncResponse response = fileService.verifySync(user, request);



        assertTrue(response.getInvalidIds().isEmpty());

        assertEquals(1, response.getValidItems().size());

        assertEquals(11L, response.getValidItems().get(0).getFileId());

        assertEquals("photos/a.jpg", response.getValidItems().get(0).getRelativePath());

    }



    @Test

    void verifySync_rebuildsWhenMetadataMatches() throws Exception {

        Path userRoot = tempDir.resolve("test");

        Files.createDirectories(userRoot.resolve("photos"));

        Path photo = userRoot.resolve("photos/2024/01/a.jpg");

        Files.createDirectories(photo.getParent());

        Files.writeString(photo, "hello");



        long capturedAt = 1_700_000_000_000L;

        CloudFile file = activeFile(20L, "photos/2024/01/a.jpg");

        file.setFileSize(5L);

        file.setSha256("abc123");

        file.setFileType("PHOTO");

        file.setCapturedAt(TimeUtil.fromEpochMs(capturedAt));

        file.setUpdatedAt(TimeUtil.now());

        when(cloudFileMapper.selectList(any())).thenReturn(List.of(file));



        VerifySyncItemDto item = new VerifySyncItemDto();

        item.setLocalUri("content://media/external/images/1");

        item.setRelativePath("photos/2024/01/a.jpg");

        item.setFileSize(5L);

        item.setCapturedAt(capturedAt);

        item.setFileName("a.jpg");

        item.setMediaType("IMAGE");

        item.setMediaId(1L);



        VerifySyncRequest request = new VerifySyncRequest();

        request.setItems(List.of(item));

        VerifySyncResponse response = fileService.verifySync(user, request);



        assertTrue(response.getInvalidIds().isEmpty());

        assertTrue(response.getValidItems().isEmpty());

        assertEquals(1, response.getRebuiltRecords().size());

        SyncRecordDto rebuilt = response.getRebuiltRecords().get(0);

        assertEquals("content://media/external/images/1", rebuilt.getLocalUri());

        assertEquals(20L, rebuilt.getRemoteFileId());

        assertEquals("photos/2024/01/a.jpg", rebuilt.getRemotePath());

        assertEquals("abc123", rebuilt.getFileHash());

    }



    @Test

    void verifySync_rebuildsByFileHashWithoutCapturedAt() throws Exception {

        Path userRoot = tempDir.resolve("test");

        Files.createDirectories(userRoot.resolve("photos"));

        Path photo = userRoot.resolve("photos/dl.jpg");

        Files.writeString(photo, "hello");



        CloudFile file = activeFile(30L, "photos/dl.jpg");

        file.setFileSize(5L);

        file.setSha256("hash-dl-abc");

        file.setFileType("PHOTO");

        file.setCapturedAt(TimeUtil.fromEpochMs(1_600_000_000_000L));

        when(cloudFileMapper.selectOne(any())).thenReturn(file);



        VerifySyncItemDto item = new VerifySyncItemDto();

        item.setLocalUri("content://media/external/images/99");

        item.setFileHash("hash-dl-abc");

        item.setRelativePath("photos/dl.jpg");

        item.setFileName("dl.jpg");

        item.setMediaType("IMAGE");



        VerifySyncRequest request = new VerifySyncRequest();

        request.setItems(List.of(item));

        VerifySyncResponse response = fileService.verifySync(user, request);



        assertTrue(response.getInvalidIds().isEmpty());

        assertEquals(1, response.getRebuiltRecords().size());

        assertEquals(30L, response.getRebuiltRecords().get(0).getRemoteFileId());

        assertEquals("hash-dl-abc", response.getRebuiltRecords().get(0).getFileHash());

    }



    @Test

    void verifySync_skipsRebuildWhenSizeMismatch() {

        CloudFile file = activeFile(21L, "photos/b.jpg");

        file.setFileSize(100L);

        file.setCapturedAt(TimeUtil.fromEpochMs(1_700_000_000_000L));

        when(cloudFileMapper.selectList(any())).thenReturn(List.of(file));



        VerifySyncItemDto item = new VerifySyncItemDto();

        item.setLocalUri("content://media/external/images/2");

        item.setRelativePath("photos/b.jpg");

        item.setFileSize(999L);

        item.setCapturedAt(1_700_000_000_000L);



        VerifySyncRequest request = new VerifySyncRequest();

        request.setItems(List.of(item));

        VerifySyncResponse response = fileService.verifySync(user, request);



        assertTrue(response.getRebuiltRecords().isEmpty());

    }



    private CloudFile activeFile(long id, String relativePath) {

        CloudFile file = new CloudFile();

        file.setId(id);

        file.setUserId(user.getId());

        file.setRelativePath(relativePath);

        file.setShare(0);

        return file;

    }

}


