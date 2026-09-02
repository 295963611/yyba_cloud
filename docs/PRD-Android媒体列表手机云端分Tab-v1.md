# 悠悠爸家庭云盘 · Android 媒体列表「手机 / 云端」分 Tab PRD

| 属性 | 内容 |
|------|------|
| 文档版本 | v1.1 |
| 状态 | 待评审 |
| 产品模块 | Android 客户端 · 相册 / 视频 / 音频 |
| 关联文档 | `产品说明-Android客户端-v1.md`、`PRD-Android启动发现与数据隔离-v1.md`、`PRD-云端下载本地去重与关联-v1.md` |
| 动机 | 当前「我的」Tab 将本地与云端合并展示，状态判定依赖异步索引与映射交叉，易产生误标、闪烁与理解成本 |

---

## 1. 需求背景

### 1.1 现状问题

| 问题 | 描述 |
|------|------|
| **数据源混排** | 「我的」同时展示 MediaStore 本地项 + sync-index 云端独有项，一条列表两种语义 |
| **状态标签过多** | 未同步 / 已同步 / 已分享 / 仅云端 混在同一视图，用户难以建立心智模型 |
| **判定链路长** | 列表态依赖 `sync_records` × `cloud_index` 异步 reconcile，易出现先全未同步、后全已同步等闪烁 |
| **操作边界模糊** | 「仅云端」项与本地项共用列表，下载、删除、分享的操作对象不一致，易误操作 |

### 1.2 核心诉求（一句话）

> **本地看本地、云端看云端；状态只回答当前 Tab 关心的问题，默认先看手机。**

### 1.3 竞品对标

| 产品 | 本地 / 云端 | 评价 |
|------|-------------|------|
| **Google 相册** | 设备相册 vs 云端备份（Backup）分开展示 | 备份状态清晰；不混排 |
| **百度网盘** | 「本地」与「云端」分入口 | 路径明确，下载态独立 |
| **Synology Photos** | 手机 / NAS 分视图 | 与家庭 NAS 场景接近 |
| **当前「我的」** | 合并 + 四种标签 | 信息密度高但易误解 |

**本产品取向**：对标 **NAS 伴侣 + 清晰备份态**——相册/视频/音频采用 **手机 | 云端** 双 Tab；**家庭 / 我分享** 仍为独立 Tab（分享关系，不与备份混排）。

---

## 2. 产品目标

### 2.1 目标

1. **删除「我的」Tab**（相册、视频、音频三类），改为 **手机 + 云端** 两个 Tab。
2. **默认进入「手机」Tab**，符合「先看本机有什么」的使用习惯。
3. **手机 Tab 只展示本机媒体**，状态仅 **未同步 / 已同步**（+ 可选 **已分享** 角标，见 §4.2）。
4. **云端 Tab 只展示当前账号云端索引项**，状态仅 **未下载 / 已下载**。
5. **降低状态 Bug 面**：各 Tab 单一数据源、单一判定维度，避免 merge 逻辑。

### 2.2 非目标（本期不做）

- **文档 / 其他** 类型列表的结构改造（仍可按旧「我的」或后续单独立项）
- **家庭 / 我分享** Tab 交互大改（仅调整 Tab 顺序与默认页）
- 多环境本地数据长期并存
- 云端 Tab 内按文件夹树浏览（仍用 sync-index 扁平列表 + 分页）

---

## 3. 信息架构变更

### 3.1 变更前（相册 / 视频 / 音频）

```
┌─────────┬─────────┬───────────┐
│  我的   │  家庭   │  我分享   │  ← 默认「我的」
└─────────┴─────────┴───────────┘
     ↑ 本地 + 云端混排
```

### 3.2 变更后

```
┌─────────┬─────────┬─────────┬───────────┐
│  手机   │  云端   │  家庭   │  我分享   │  ← 默认「手机」
└─────────┴─────────┴─────────┴───────────┘
   本地      云端      分享入      分享出
```

| Tab | 数据源 | 状态标签 | 默认 |
|-----|--------|----------|------|
| **手机** | MediaStore（本机扫描） | 未同步、已同步 | ✓ |
| **云端** | sync-index（当前环境云端索引） | 未下载、已下载 | |
| **家庭** | 服务端「分享给我」 | 无同步态（只读浏览） | |
| **我分享** | 服务端「我分享的」 | 无同步态 | |

---

## 4. 功能定义

### 4.1 手机 Tab

**展示范围**

- 仅本机 MediaStore 中的照片 / 视频 / 音频（与当前 category 一致）。
- **不展示**「仅存在于云端、本机没有」的项（这些只在 **云端 Tab** 出现）。

**状态规则**

| 状态 | 判定（产品语义） | 用户理解 |
|------|------------------|----------|
| **未同步** | 本机有文件，且尚未与当前环境云端索引建立有效关联 | 还没备份到家里 |
| **已同步** | 本机有文件，且 `sync_records` 映射的 `remotePath` 存在于当前 `cloud_index` | 已经备份成功 |

**补充**

- **已分享**：在 **已同步** 基础上，若该云端文件已分享给家庭，可叠加 **已分享** 角标（不改变主状态为未同步/已同步）。
- 云端索引 **尚未加载完成** 时：宁可显示 **未同步**，索引就绪后刷新；**禁止**用文件名+大小猜测已同步（与现网严格规则一致）。
- 首页「未同步 X 张」统计与 **手机 Tab 未同步** 口径一致。

**主要操作**

| 操作 | 说明 |
|------|------|
| 单条 / 批量上传 | 对 **未同步** 项触发同步 |
| 删除 | 删除 **本机文件**；若已有 sync 映射，同步清理映射（是否删云端另确认，沿用现网策略） |
| 分享 / 取消分享 | 仅对 **已同步** 项可用 |
| 预览 | 本机 URI 直接预览 |

### 4.2 云端 Tab

**展示范围**

- 仅当前登录环境 `sync-index` 中、属于当前 category 的云端文件。
- **不展示** 纯本机未上传项。

**状态规则**

| 状态 | 判定（产品语义） | 用户理解 |
|------|------------------|----------|
| **未下载** | 云端索引有该项，本机尚无可用的本地副本 | 需要下载到手机 |
| **已下载** | 满足以下任一即可：<br>① `sync_records` 存在且 localUri 指向的文件仍存在；<br>② 按去重规则（路径/哈希）检测到本机已有等价文件 | 手机已经有了 |

> **「已下载」包含「本地本来就有、后来才上传」的情况**：只要本机存在等价文件，即显示已下载，不要求用户必须点过一次下载。

**主要操作**

| 操作 | 说明 |
|------|------|
| 下载 | 对 **未下载** 项；下载完成后变为 **已下载**，并写入/更新 `sync_records` |
| 删除 | 删除 **云端文件**（进回收站策略沿用现网）；本机副本是否保留需二次确认 |
| 预览 | 已下载走本机；未下载走云端流式预览（若现网支持） |
| 分享 | 若产品允许从云端 Tab 分享，仅针对云端项（P1，可与现网对齐） |

**不再使用的标签**

- 废除在手机 Tab 出现的 **「仅云端」** 标签——语义由 **云端 Tab + 未下载** 承接。

### 4.3 相册 / 视频 / 音频 一致性

三类媒体 **共用同一 Tab 结构与状态机**，仅 category 与扫描源不同：

| 类型 | 手机 Tab 数据源 | 云端 Tab 数据源 |
|------|-----------------|-----------------|
| 相册 | MediaStore IMAGE | sync-index `photos/` |
| 视频 | MediaStore VIDEO | sync-index `videos/` |
| 音频 | MediaStore AUDIO | sync-index `audio/` |

文档 / 其他：**不在本期范围**；入口文案可暂保留「我的」或后续跟进。

### 4.4 家庭 / 我分享 Tab

- **保留**，逻辑与现网一致。
- Tab 顺序：**手机 → 云端 → 家庭 → 我分享**。
- 从 **手机 Tab 分享** 成功后，「我分享」Tab 应刷新；**家庭 Tab** 不受备份态影响。

---

## 5. 用户故事与验收标准

### US-1 默认看手机相册

**作为** 用户，**当我** 从首页进入相册，**我希望** 默认看到手机里的照片及备份状态，**以便** 快速知道哪些还没上传。

**验收标准**

- [ ] 进入相册/视频/音频，ViewPager 默认停在 **手机** Tab（position 0）。
- [ ] 首屏仅含 MediaStore 项，无云端独有项。
- [ ] 每项仅显示 **未同步** 或 **已同步**（+ 可选已分享角标）。

### US-2 云端补下载

**作为** 用户，**当我** 换机或在别的设备上传了照片，**我希望** 在 **云端** Tab 看到并下载，**以便** 补全本机缺失文件。

**验收标准**

- [ ] **云端** Tab 列表仅来自 sync-index，不含纯本地未上传项。
- [ ] 本机无副本 → **未下载**；本机有副本（含上传前就存在的）→ **已下载**。
- [ ] 下载成功后，同一项在云端 Tab 变为 **已下载**；若手机 Tab 也有对应本地文件，显示 **已同步**。

### US-3 状态不再混标

**作为** 用户，**当我** 刚登录或索引加载中，**我希望** 状态不会短暂全错，**以便** 信任 App 显示。

**验收标准**

- [ ] 手机 Tab：索引未就绪时，已有关联记录的项不误标为已同步；无关联一律未同步。
- [ ] 云端 Tab：索引加载前有明确 loading/骨架，不出现空列表误判为「云端无文件」。
- [ ] 索引 reconcile 完成后，两 Tab 各自刷新，**互不依赖 merge 行类型**（废除 `cloudOnly` 与 `local` 混排一行模型）。

### US-4 首页未同步统计一致

**作为** 用户，**我希望** 首页「未同步照片 X 张」与相册 **手机 Tab** 筛选未同步数量一致。

**验收标准**

- [ ] 统计口径 = MediaStore + 与手机 Tab 相同的已同步判定。
- [ ] 上传完成 / 删除映射 / 切换账号后，首页数字与手机 Tab 同步更新。

### US-5 分享能力不回归

**作为** 用户，**我希望** 仍能从已备份内容分享给家庭，并在 **我分享** Tab 查看。

**验收标准**

- [ ] 手机 Tab 中 **已同步** 项可分享/取消分享。
- [ ] **家庭 / 我分享** Tab 行为与改版前一致。
- [ ] 分享后角标正确，不影响未同步/已同步主状态。

---

## 6. 用户操作路径

### 6.1 备份路径（主路径）

```
首页 → 相册（默认手机 Tab）
     → 看到「未同步」照片
     → 多选 → 上传 / 或首页「立即同步」
     → 手机 Tab 状态变为「已同步」
```

### 6.2 补下载路径

```
首页 → 相册 → 切换到「云端」Tab
     → 看到「未下载」
     → 下载
     → 云端 Tab →「已下载」；手机 Tab 出现对应项 →「已同步」
```

### 6.3 换账号后

```
登录新账号 → 环境隔离清空索引与映射
     → 手机 Tab：全部「未同步」（旧映射已清）
     → 云端 Tab：拉取新账号 sync-index
```

---

## 7. 旧逻辑清理清单（该删则删）

> **原则**：凡是为「我的 Tab 本地+云端混排」服务的代码、文案、文档，改版后 **删除或迁移**；不再保留双轨兼容。后台 reconcile / 去重能力 **保留**，但只服务于两 Tab 各自刷新，不再驱动 merge。

### 7.1 产品概念 · 废除

| 废除项 | 原用途 | 替代 |
|--------|--------|------|
| **「我的」Tab** | 本地+云端合并入口 | **手机** + **云端** 两个 Tab |
| **「仅云端」状态标签** | 混排列表中标识云端独有项 | **云端 Tab → 未下载** |
| **「下载后合并为一条」** | 同列表内 cloudOnly 行消失、local 行变已同步 | **跨 Tab**：云端 Tab → 已下载；手机 Tab → 已同步（可仍为两条列表记录，各 Tab 各管各的） |
| **四种标签混排** | 未同步/已同步/已分享/仅云端同屏 | 手机：未同步/已同步（+已分享角标）；云端：未下载/已下载 |

### 7.2 文档 · 修订或标注废弃

| 文档 | 动作 |
|------|------|
| `产品说明-Android客户端-v1.md` | §3.3、§4.2、§4.3、§4.6、§6 场景 B/C、术语表：删除「我的/仅云端/合并一条」表述，改为手机/云端分 Tab（**P0 随开发同步**） |
| `PRD-云端下载本地去重与关联-v1.md` | 在文首加 **「部分 UX 已由本分 Tab PRD 替代」** 说明：去重与 `SyncRecord` 规则 **保留**；「同列表合并一条」改为 **双 Tab 状态联动** |
| `问题分析-相册闪屏与幽灵云端数据.md` | 标注 **历史问题**；根因中的 staged merge / cloudOnly 混排在新架构下不再存在 |
| `PRD-Android启动发现与数据隔离-v1.md` § 切换环境 | 「仅云端已下载文件」改为 **云端 Tab 已下载 / 手机 Tab 已同步** 表述 |

### 7.3 Android 代码 · 删除（P0）

**Tab / 常量 / 资源**

| 删除或替换 | 位置 |
|------------|------|
| `TAB_MINE = "mine"` | `MediaListAdapter` → `TAB_PHONE` / `TAB_CLOUD` |
| 默认 `tab_type = "mine"` | `MediaTabFragment`、`MediaTabPagerAdapter` |
| `tab_mine`、`cloud_only` 文案 | `strings.xml` → `tab_phone`、`tab_cloud`、`status_not_downloaded`、`status_downloaded` |
| `menu_media_mine.xml` | 拆为 `menu_media_phone.xml` + `menu_media_cloud.xml` |
| `MediaListActivity` position 0 显示「我的」 | 改为 手机 / 云端 / 家庭 / 我分享（4 Tab） |

**「我的」混排核心逻辑（整段删除，不保留 feature flag）**

| 删除项 | 位置 | 说明 |
|--------|------|------|
| `mergeCloudRows()` | `MediaTabFragment` | 本地页 append 云端行的 merge 入口 |
| `shouldLoadCloudRows()` | `MediaTabFragment` | 判断 mine Tab 是否继续拉云端 |
| `useStagedLocalFirst()` + `loadFirstPageStaged()` 内 cloud merge | `MediaTabFragment` | 先本地后 merge 云端的两段式加载 |
| `prepareMineTabState()` | `MediaTabFragment` | 拆为 `preparePhoneTabState()` / `prepareCloudTabState()` |
| 同一 Tab 内 `localExhausted` + `cloudExhausted` 双 exhausted | `MediaTabFragment` | 各 Tab 独立分页，不再共用 |
| `cloudMergePending` | `MediaTabFragment` | merge 等待态 |
| `sortRowsByCapturedTime()` 混排 local+cloud | `MediaTabFragment` | 可保留 **Tab 内** 排序，但不再 cross-type sort |
| `loadCloudRows()` 中 `isKnownLocalPath` 过滤后 `cloudOnly` 进 mine 列表 | `MediaTabFragment` | 云端加载迁到 **云端 Tab** 专用路径 |
| `loadCloudRowsFromSyncIndex()` 排除已映射路径以产 cloudOnly | mine 分支 | 云端 Tab：**展示全部** sync-index 项，用 **已下载** 标态，不再从列表排除 |
| `MediaRow.cloudOnly()` 用于 mine Tab | `MediaListAdapter` | 云端 Tab 专用 cloud 行工厂（可 rename `cloudIndexRow`） |
| `removeCloudOnlyDuplicates()` | `MediaListAdapter` | 为防混排重复而删 cloudOnly 行 |
| `removeCloudRowsWithPath()` | `MediaListAdapter` | 下载后从同列表删 cloudOnly；改由 **云端 Tab 刷新状态** |
| `removeDuplicateLocalRows()` / `upsertSyncedLocalRow()` 的 merge 语义 | `MediaListAdapter` | 下载后 **刷新两 Tab**，不在单列表 upsert |
| mine Tab 下 `bindCloudRow` 显示 `cloud_only` | `MediaListAdapter` | 云端 Tab 显示 未下载/已下载 |
| 所有 `"mine".equals(tabType)` 分支 | 多处 | 改为 `phone` / `cloud` 明确分支 |
| `SharedMediaPrefetchCache` 的 `prefetch(..., "mine", ...)` | 预取 | 改为 `phone` + `cloud` 或仅 cloud index |

**单元测试**

| 删除/改写 | 说明 |
|-----------|------|
| 依赖 `TAB_MINE`、`cloudOnly` 混排的测试 | 改为 phone/cloud 分 Tab 用例 |
| 「merge 后只剩一条」类断言 | 改为双 Tab 状态断言 |

### 7.4 Android 代码 · 保留但改职责

| 保留项 | 改版后职责 |
|--------|------------|
| `SyncStatusHelper.isSyncedFast` / `isRecordSyncedOnServer` | **仅手机 Tab** |
| 新增 `isDownloadedFast`（或同级方法） | **仅云端 Tab** |
| `buildCloudPathByNameSize`、`reconcileDisk` | **后台**补 `sync_records`；完成后分别 `refreshPhoneTab()` / `refreshCloudTab()` |
| `CloudMediaDownloadHelper` | 下载 + 写 `SyncRecord`；**不再**删同列表 cloudOnly，改为通知云端 Tab + 手机 Tab 刷新 |
| `CloudIndexStore` / `ServerSyncCache` | 云端 Tab 数据源；注释去掉「识别仅云端项（用于 mine 混排）」 |
| `scheduleBackgroundReconcile` | 保留；回调内 **禁止** `mergeCloudRows`，改为双 Tab refresh |
| `applySyncState` | 仅作用于 **手机 Tab** adapter |
| 新增 `applyDownloadState` | 仅作用于 **云端 Tab** adapter |
| `MediaRow.local()` | 手机 Tab |
| `MediaRow.sharedCloud()` | **家庭 / 我分享** Tab（不变） |

### 7.5 关联 PRD 口径调整

**原 `PRD-云端下载本地去重与关联` 验收项** → **新验收**

| 旧验收 | 新验收 |
|--------|--------|
| 下载后「我的照片」**仅 1 条** | 手机 Tab 有对应项且 **已同步**；云端 Tab 同路径 **已下载** |
| 「仅云端」行 **立即消失** | 云端 Tab 该项标签由 **未下载 → 已下载**（项仍在云端列表） |
| 列表 merge / removeCloudOnly | **删除**；不允许再实现「单列表合并」 |

---

## 8. 与现网实现差异（供研发评估）

| 维度 | 现网「我的」 | 目标 |
|------|--------------|------|
| Tab 数 | 3（我的/家庭/我分享） | 4（手机/云端/家庭/我分享） |
| 列表行类型 | `local` + `cloudOnly` 混排 | 两 Tab 各一种数据源 |
| 状态 Helper | 同一列表两套语义 | 手机用 sync 判定；云端用 download 判定 |
| 分页 | 本地页 + 云端 merge 页 | 手机：MediaStore 分页；云端：index 分页 |
| 菜单 | `menu_media_mine` | 手机菜单（上传/删本地/分享）；云端菜单（下载/删云端） |
| 下载后 UI | 同列表删行 + upsert | 双 Tab 各自 refresh |

**预估工作量**：中等（删 merge 链路 + 拆 Tab 比纯加 UI 更省长期维护成本）。

---

## 9. 优先级

| 优先级 | 范围 |
|--------|------|
| **P0** | 相册：手机/云端 Tab、状态、默认手机 |
| **P0** | **删除** §7.3 所列 mine 混排代码（不保留兼容层） |
| **P0** | 视频、音频：与相册同结构 |
| **P0** | 首页未同步统计与手机 Tab 口径对齐 |
| **P0** | 同步更新 `产品说明-Android客户端-v1.md` |
| **P1** | 云端 Tab 批量下载、删除云端二次确认文案优化 |
| **P1** | `PRD-云端下载本地去重与关联` 文首补充替代说明 |
| **P2** | 文档 / 其他类型是否跟进分 Tab |

---

## 10. 风险与对策

| 风险 | 对策 |
|------|------|
| Tab 增至 4 个，栏位拥挤 | 使用短标签「手机」「云端」；小屏可滚动 Tab |
| 用户找「以前我的里云端那条」 | 引导：云端内容统一在 **云端 Tab**；首次升级可选一次性 Toast |
| 「已下载」与「已同步」不同 Tab 不同词 | 产品 copy 固定：手机问「备没备份」，云端问「下没下载」 |
| 本机已有文件但无 sync_records | 云端 Tab 用去重检测标 **已下载**；reconcile 后台补映射（不阻塞 UI） |

---

## 11. 评审结论（待填）

| 角色 | 结论 | 日期 |
|------|------|------|
| 产品 | | |
| 研发 | | |
| 测试 | | |

---

## 附录 A：研发删除检查表（Merge 相关）

开发完成后，代码库中 **不应再出现** 下列模式（测试与注释除外历史说明）：

- [ ] `"mine".equals(tabType)` 用于相册/视频/音频列表
- [ ] `mergeCloudRows`
- [ ] `shouldLoadCloudRows`
- [ ] `cloudMergePending`
- [ ] `MediaRow.cloudOnly` 在手机 Tab 数据流中被创建
- [ ] `R.string.cloud_only` 作为手机 Tab 或混排列表状态
- [ ] `removeCloudOnlyDuplicates` / 下载后 `removeCloudRowsWithPath` 作为唯一 UI 更新手段
- [ ] `tab_mine` / `menu_media_mine` 资源仍被相册/视频/音频使用

---

## 附录 B：状态判定简表（研发参考）

**手机 · 已同步**

```
exists(localFile)
AND sync_records[localUri].remotePath ∈ cloud_index.serverPaths
AND remotePath 非「云端已删隐藏」态
```

**云端 · 已下载**

```
exists(localEquivalentFile)
OR (sync_records 指向的 localUri 文件存在)
```

**云端 · 未下载**

```
cloud_index 有项 AND NOT 已下载
```
