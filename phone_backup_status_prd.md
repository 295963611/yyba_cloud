# 手机备份走 MediaStore 统一方案

## 1. 决策：手机备份也走 MediaStore

### 1.1 为什么这是最优解

之前所有问题——localUri 三重格式、mediaType=null、localMediaExists() 恒 false、Tier 0/1/2 跨入口漏判——**根源都是手机备份用 java.io.File，而相册同步用 MediaStore**。

如果手机备份也走 MediaStore，localUri 统一为 `content://media/external/<collection>/<id>` 格式：

| 问题 | 修复前 | 修复后 |
|------|--------|--------|
| localUri 不一致 | content:// vs /storage/ vs file:/// | **统一 content://media/...** |
| localUri 三重格式 | file:/// /storage/ content:// | **只有 content://media/...** |
| mediaType = null | BackupEngine 传 null | MediaStore 自带 MEDIA_TYPE |
| mediaId = 0 | BackupEngine 传 0 | MediaStore 自带 _ID |
| localMediaExists() 恒 false | 非 content:// 无法解析 | **全部 content://，正常工作** |
| 跨入口 Tier 0/1/2 | 需要三级匹配 | **Tier 0 精确匹配 + Tier 2 兜底即可** |
| 历史脏数据迁移 | 需要迁移 file:/// | **不需要，新写入格式正确** |

### 1.2 手机备份产品定位调整

| 维度 | 之前（File 路线） | 现在（MediaStore 路线） |
|------|-----------------|----------------------|
| 备份范围 | 任意文件（含非媒体） | **仅限 MediaStore 索引的文件** |
| 覆盖类型 | / | IMAGE / VIDEO / AUDIO / DOCUMENT / OTHER |
| 浏览方式 | 文件管理器目录树 | **按 MediaStore 集合 + RELATIVE_PATH 分组** |
| localUri | /storage/... 或 file:///... | **content://media/external/...** |
| mediaType | null（需自己推断） | MediaStore 自带 |
| mediaId | 0（需自己填） | MediaStore 自带 _ID |

**关于 PDF/Excel/Word**：Android MediaStore 索引文档的方式是：
- `MEDIA_TYPE_DOCUMENT`：Android 10+，部分 OEM 不设这个
- `MEDIA_TYPE_NONE` + MIME_TYPE LIKE '%pdf%' / 'application/msword' 等

当前 MediaScanner 的 DocumentQuery 已经处理了这些（按扩展名扫描 Office/PDF），手机备份可以复用同一套查询。

## 2. 范围

### 2.1 在范围内

| 模块 | 改动 | 类型 |
|------|------|------|
| LocalFileBrowser | **重写**：从 File.list() → MediaStore 查询 + RELATIVE_PATH 分组 | 架构转变 |
| BackupEngine | **重写**：从 File → MediaStore content:// Uri + 复用 MediaScanner 取元数据 | 架构转变 |
| BackupStatusResolver | **简化**：Tier 0 localUri 精确匹配即可，Tier 0b/1 降级为可选 | 大幅简化 |
| FileHashVerifyScheduler | **保持**：Tier 2 网络校验 | 不变 |
| SyncRecord | **保持**：字段已齐全 | 不变 |
| ChunkUploader | **保持**：落库 localUri 由传入的 uri.toString() 决定 | 不变（现在传入的就是 content://） |
| PhoneBackupActivity | **适配**：目录浏览 UI 从 File 树 → MediaStore 分组列表 | UX 调整 |

### 2.2 不在范围内

- 相册同步侧（已经走 MediaStore，不动）
- SyncVerifyScheduler（保持）
- 服务端接口（保持全局 hash 去重）
- SyncRecord 表结构（保持）

## 3. 核心流程

### 3.1 手机备份浏览流程（重构后）

```
PhoneBackupActivity.loadDir()
  ↓
MediaStore 查询（按 RELATIVE_PATH 过滤）
  ↓
返回 MediaItem 列表（uri = content://media/...）
  ↓
BackupStatusResolver.fillStatus()
  ↓
Tier 0: dao.findByLocalUri(mediaItem.uri.toString())
  ├─ 命中 → BACKED_UP（相册同步 + 手机备份，都用 content://，100% 匹配）
  └─ 未命中 → 进入 Tier 2
  ↓
Tier 2: FileHashVerifyScheduler.verifyNow()
  ├─ 服务端确认已存在 → BACKED_UP + 回填 SyncRecord
  └─ 服务端确认不存在 → UNCHECKED
```

**Tier 0b（fileName+fileSize 预筛）是否还需要？**

理论上不需要——因为两侧都用 content://，Tier 0 localUri 应该 100% 命中。但有一个边界场景：

> MediaStore 索引更新有延迟：相册同步上传后，MediaStore 可能还没刷新，PhoneBackupActivity 看到的是旧的 MediaStore _ID，导致两个 SyncRecord 的 localUri 指向不同的 MediaStore _ID，但实际是同一物理文件。

这个场景概率极低（MediaStore 在文件落盘后通常几秒内刷新），且 Tier 2 能兜底。**结论：Tier 0b 暂不实现，保留接口以备未来需要。**

### 3.2 手机备份上传流程（重构后）

```
用户勾选 MediaItem（uri = content://media/...）
  ↓
BackupEngine.backup(List<MediaItem>)
  ↓
对每个 MediaItem：
  1. 用 ContentResolver 从 uri 读流（ChunkUploader 内部已支持）
  2. 从 MediaItem 取 fileName / fileSize / mediaType / takenAt / modifiedAt
  3. 构建 relativePath（复用现有 phoneBackup/<device>/<RELATIVE_PATH>/<fileName> 规则）
  4. ChunkUploader.uploadMedia(uri, fileName, mediaType, modifiedAt, takenAt, mediaId, relativePath)
  ↓
ChunkUploader.doUpload() 落库：
  record.localUri = uri.toString()  → "content://media/external/images/media/12345"
  record.mediaId = mediaId          → 12345（MediaStore _ID）
  record.mediaType = mediaType      → "IMAGE"
  record.fileHash = hash            → SHA-256（不变）
  record.remotePath = relativePath  → "phoneBackup/deviceName/DCIM/Camera/IMG_001.jpg"
  record.remoteFileId = ...
```

### 3.3 跨入口匹配流程（统一后）

```
同一物理文件 IMG_001.jpg：

相册同步写入 SyncRecord:
  localUri = "content://media/external/images/media/12345"
  mediaId = 12345
  mediaType = "IMAGE"
  remotePath = "photos/2024/01/IMG_001.jpg"

手机备份写入 SyncRecord:
  localUri = "content://media/external/images/media/12345"  ← 同一个！
  mediaId = 12345
  mediaType = "IMAGE"
  remotePath = "phoneBackup/deviceName/DCIM/Camera/IMG_001.jpg"

手机备份列表判定：
  dao.findByLocalUri("content://media/external/images/media/12345")
  → 命中！返回有 remoteFileId 的那条
  → BACKED_UP ✓

相册 Tab 判定：
  dao.findByLocalUri("content://media/external/images/media/12345")
  → 命中！返回有 remoteFileId 的那条
  → BACKED_UP ✓
```

## 4. UX 设计

### 4.1 从文件树到 MediaStore 分组

之前 PhoneBackupActivity 是 `File.list()` 目录树浏览，改成 MediaStore 后需要重新设计：

**方案 A：按 RELATIVE_PATH 分组展示（推荐）**

```
┌─────────────────────────────┐
│ 手机备份                      │
├─────────────────────────────┤
│ 📁 DCIM/Camera       (12)    │  ← RELATIVE_PATH 分组
│ 📁 DCIM/Screenshots   (8)    │
│ 📁 Downloads          (3)    │
│ 📁 Documents          (5)    │
│ 📁 WhatsApp/...       (7)    │
│ 📷 最近照片          (24)    │  ← 按时间分组（可选）
└─────────────────────────────┘
```

点击分组进入列表，显示该 RELATIVE_PATH 下的所有 MediaStore 条目。

**方案 B：按媒体类型分类**

```
┌─────────────────────────────┐
│ 手机备份                      │
├─────────────────────────────┤
│ 📷 照片              (234)   │
│ 🎬 视频               (56)   │
│ 🎵 音频               (12)   │
│ 📄 文档               (34)   │
└─────────────────────────────┘
```

点击类型进入列表，按 RELATIVE_PATH 或时间排序。

**推荐方案 A**：用户更熟悉自己的文件在哪个目录，而不是文件类型。且与相册 Tab 的 MediaStore 查询模式一致。

### 4.2 浏览界面细节

| 项目 | 行为 |
|------|------|
| 分组键 | `RELATIVE_PATH`（MediaStore 自带） |
| 分组排序 | 按文件数降序，或按修改时间降序 |
| 列表排序 | 按 `DATE_TAKEN`（图片/视频）或 `DATE_MODIFIED`（其他）降序 |
| 已备份文件 | checkbox 禁用 + 置灰勾选 + 显示 ✓ 标记 |
| 下拉刷新 | 重新查询 MediaStore + 刷新 SyncRecord 状态 |

### 4.3 MediaStore 查询适配 RELATIVE_PATH

```java
// 查询 RELATIVE_PATH 分组列表
String[] projection = { MediaStore.Files.FileColumns.RELATIVE_PATH };
String sortOrder = "COUNT(*) DESC";  // SQLite 聚合
// ... ContentResolver.query ...

// 查询某 RELATIVE_PATH 下的文件
String selection = MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?";
String[] selectionArgs = new String[]{ folderPath + "%" };
// ... ContentResolver.query → 返回 MediaItem 列表
```

## 5. Android 11+ Scoped Storage 约束

### 5.1 MediaStore 可见性

| 访问方式 | Android 10 (API 29) | Android 11+ (API 30+) |
|----------|---------------------|----------------------|
| `requestLegacyExternalStorage=true` | ✅ 所有目录 | ❌ 被忽略 |
| MediaStore + READ_EXTERNAL_STORAGE | ✅ 所有媒体文件 | ✅ 应用自有目录 + 共享媒体集合 |
| MediaStore + READ_MEDIA_IMAGES/VIDEO/AUDIO | ❌ 不存在 | ✅ 精准权限 |
| MANAGE_EXTERNAL_STORAGE | ❌ 不存在 | ✅ 全量访问（需引导用户授权） |

**当前项目 targetSdk**：需确认。如果是 30+，需要处理分区存储。

### 5.2 文档类文件的 MediaStore 覆盖

Android 11+ MediaStore 对文档类文件（PDF、DOCX 等）的覆盖：
- `MediaStore.Files.getContentUri("external")` 索引所有文件，包括非媒体
- 但 `RELATIVE_PATH` 对应用私有目录外的文件通常返回根路径下的相对值
- 部分 OEM 不设 `MEDIA_TYPE_DOCUMENT`，按 MIME_TYPE 或扩展名扫描更可靠

**建议**：复用 MediaScanner.DocumentQuery 的扩展名匹配逻辑，确保文档类文件被覆盖。

## 6. 功能需求

### FR-12 LocalFileBrowser 重构为 MediaStore 查询

| 项目 | 内容 |
|------|------|
| 现状 | `File.list()` 目录树遍历 |
| 改造 | MediaStore.Files 查询，按 RELATIVE_PATH 分组返回 |
| 接口 | `listGroups()` → 返回分组列表；`listInGroup(String relativePath)` → 返回该组文件列表 |
| 文件类型 | IMAGE / VIDEO / AUDIO / DOCUMENT（按扩展名匹配） |
| 权限 | READ_EXTERNAL_STORAGE（Android 10-）或 READ_MEDIA_IMAGES/VIDEO/AUDIO（Android 13+） |

### FR-13 BackupEngine 重构为 MediaStore Uri 驱动

| 项目 | 内容 |
|------|------|
| 现状 | `new File(absPath)` → `Uri.fromFile()` → `ChunkUploader.uploadMedia()` |
| 改造 | MediaItem.uri（content://media/...）→ 直接传 ChunkUploader.uploadMedia() |
| 参数 | 从 MediaItem 取 fileName / fileSize / mediaType / takenAt / modifiedAt / mediaId |
| relativePath | 复用 phoneBackup/<device>/<RELATIVE_PATH>/<fileName> |

### FR-14 BackupStatusResolver 简化为单路匹配

| 项目 | 内容 |
|------|------|
| Tier 0 | `dao.findByLocalUri(mediaItem.uri.toString())` —— 两侧都用 content://，100% 匹配 |
| Tier 0b | **暂不实现**（保留接口以备 MediaStore _ID 不一致边界场景） |
| Tier 1 | **移除**（不再需要消歧） |
| Tier 2 | 保留 FileHashVerifyScheduler 兜底 |

### FR-15 PhoneBackupActivity 浏览 UI 适配

| 项目 | 内容 |
|------|------|
| 分组列表 | RELATIVE_PATH 分组，显示路径 + 文件数 |
| 文件列表 | 进入分组后显示文件，按 DATE_TAKEN/DATE_MODIFIED 排序 |
| 状态 | 每个文件显示 BACKED_UP / UNCHECKED |
| 已备份 | checkbox 禁用 + 置灰勾选 |

### FR-16 相册侧状态匹配保持不变

| 项目 | 内容 |
|------|------|
| SyncRecordLookup.loadByLocalUris | 继续用 content:// 精确匹配 |
| SyncStatusHelper.isSynced | 继续用 content:// 精确匹配 |
| SyncVerifyScheduler | 保持不变 |

**为什么不需要改**：相册同步一直用 content://，现在手机备份也用 content://，两侧 SyncRecord.localUri 格式统一，原逻辑自然正确。

## 7. 实施步骤

| 步骤 | 内容 | 依赖 |
|------|------|------|
| 1 | LocalFileBrowser 重构为 MediaStore 查询 + RELATIVE_PATH 分组 | 无 |
| 2 | BackupEngine 重构为 MediaItem Uri 驱动 | Step 1 |
| 3 | BackupStatusResolver 简化为单路 content:// 匹配 | 无 |
| 4 | PhoneBackupActivity 浏览 UI 适配 | Step 1 |
| 5 | 清理旧代码：FileHashVerifyScheduler / 旧 File 路线 | Step 1-4 |
| 6 | 编译验证 | Step 1-5 |

## 8. 开放问题

| 编号 | 问题 | 建议 |
|------|------|------|
| Q1 | MediaStore.Files 是否覆盖 APK、CSV、TXT 等任意文件？ | Android MediaStore 索引所有文件，但部分 OEM 不索引非媒体。建议按扩展名白名单扫描，与 DocumentQuery 保持一致 |
| Q2 | 历史 file:/// /storage/ SyncRecord 如何处理？ | 迁移：启动时 UPDATE sync_records SET localUri = 'content://media/...' 但无法反查 MediaStore _ID → **建议放弃迁移，这些记录 Tier 2 网络校验时自然回填新的 content:// localUri** |
| Q3 | targetSdk 版本和 Scoped Storage 策略？ | 需确认当前 targetSdk。如果是 30+，READ_EXTERNAL_STORAGE 仅能访问应用自有目录，需申请 MANAGE_EXTERNAL_STORAGE 或引导用户授权 |
| Q4 | MediaStore 索引刷新延迟导致新文件不可见？ | File.list() 能看到刚写入的文件，但 MediaStore 索引有延迟。解决方案：上传完成后调用 `ContentResolver.notifyChange()` 或 `MediaScannerConnection.scanFile()` |
