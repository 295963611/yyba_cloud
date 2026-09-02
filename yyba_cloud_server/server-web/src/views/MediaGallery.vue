<template>
  <div class="media-gallery">
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane :label="tabLabel('mine')" name="mine" />
      <el-tab-pane :label="tabLabel('family')" name="family" />
      <el-tab-pane :label="tabLabel('my_share')" name="my_share" />
    </el-tabs>

    <div v-if="canManageShare" class="share-toolbar">
      <el-button
        :type="selectionMode ? 'primary' : 'default'"
        size="small"
        @click="toggleSelectionMode"
      >
        {{ selectionMode ? '退出多选' : '多选' }}
      </el-button>
      <template v-if="selectionMode">
        <el-button size="small" @click="selectAll">全选本页</el-button>
        <el-button size="small" @click="clearSelection">清空</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="selectedPaths.size === 0"
          :loading="shareLoading"
          @click="shareSelected(1)"
        >
          分享选中
        </el-button>
        <el-button
          size="small"
          :disabled="selectedPaths.size === 0"
          :loading="shareLoading"
          @click="shareSelected(0)"
        >
          取消分享
        </el-button>
        <el-button
          v-if="canDelete"
          type="danger"
          size="small"
          plain
          :disabled="selectedPaths.size === 0"
          :loading="deleteLoading"
          @click="deleteSelected"
        >
          删除
        </el-button>
        <el-button
          v-if="canDownload"
          size="small"
          plain
          :disabled="selectedPaths.size === 0"
          :loading="downloadLoading"
          @click="downloadSelected"
        >
          下载
        </el-button>
      </template>
      <template v-else-if="activeTab === 'mine'">
        <el-button
          type="primary"
          size="small"
          plain
          :loading="shareLoading"
          @click="shareAll(1)"
        >
          全部分享
        </el-button>
        <el-button
          size="small"
          plain
          :loading="shareLoading"
          @click="shareAll(0)"
        >
          全部取消分享
        </el-button>
      </template>
      <span v-if="selectionMode && selectedPaths.size > 0" class="sel-hint">
        已选 {{ selectedPaths.size }} 项
      </span>
    </div>

    <div
      ref="scrollRef"
      class="grid-wrap"
      v-loading="loading"
      @scroll="onScroll"
    >
      <div v-if="items.length === 0 && !loading" class="empty">暂无内容</div>
      <div class="grid">
        <div
          v-for="(item, index) in items"
          :key="item.relativePath + '-' + index"
          class="card"
          :class="{ selected: isSelected(item), selectable: selectionMode && canManageShare }"
          @click="onCardClick(item)"
        >
          <div v-if="selectionMode && canManageShare" class="sel-check" @click.stop>
            <el-checkbox
              :model-value="isSelected(item)"
              @change="val => setSelected(item, val)"
            />
          </div>
          <div v-if="item.shared && activeTab === 'mine'" class="share-badge">已分享</div>
          <div class="thumb">
            <img
              v-if="(mediaKind === 'PHOTO' || mediaKind === 'VIDEO') && item.thumbUrl"
              :src="item.thumbUrl"
              alt=""
              loading="lazy"
              @error="onThumbError(item)"
            />
            <div v-else-if="mediaKind === 'PHOTO' || mediaKind === 'VIDEO'" class="thumb-loading">
              <span>加载中…</span>
            </div>
            <div v-else class="thumb-placeholder">
              <span v-if="mediaKind === 'VIDEO'">🎬</span>
              <span v-else-if="mediaKind === 'AUDIO'">🎵</span>
              <span v-else>🖼️</span>
            </div>
          </div>
          <div class="name" :title="item.name">{{ item.name }}</div>
          <div v-if="item.ownerLabel" class="owner">{{ item.ownerLabel }}</div>
        </div>
      </div>
      <div v-if="loadingMore" class="loading-more">加载中…</div>
    </div>

    <!-- 预览 -->
    <el-dialog
      v-model="previewDialogVisible"
      :title="previewItem?.name || '预览'"
      width="800px"
      destroy-on-close
      @closed="onPreviewClosed"
    >
      <div class="preview-body">
        <img
          v-if="mediaKind === 'PHOTO' && previewSrc"
          :src="previewSrc"
          alt=""
          class="preview-image"
        />
        <video
          v-if="mediaKind === 'VIDEO' && previewSrc"
          :src="previewSrc"
          controls
          autoplay
          class="preview-video"
        />
        <audio
          v-if="mediaKind === 'AUDIO' && previewSrc"
          :src="previewSrc"
          controls
          autoplay
          class="preview-audio"
        />
      </div>
      <template v-if="canManageShare && previewItem" #footer>
        <el-button
          v-if="canDownload"
          plain
          :loading="downloadLoading"
          @click="downloadOne(previewItem)"
        >
          下载
        </el-button>
        <el-button
          v-if="canDelete"
          type="danger"
          plain
          :loading="deleteLoading"
          @click="deleteOne(previewItem)"
        >
          移入回收站
        </el-button>
        <el-button
          v-if="!previewItem.shared"
          type="primary"
          :loading="shareLoading"
          @click="shareOne(previewItem, 1)"
        >
          分享到家庭
        </el-button>
        <el-button
          v-else
          :loading="shareLoading"
          @click="shareOne(previewItem, 0)"
        >
          取消分享
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchAlbumPhotos,
  fetchSharedMedia,
  fetchSyncIndex,
  fetchConfig,
  deleteFile,
  mediaUrl,
  updateShare,
  updateShareAll
} from '../api'

const props = defineProps({
  mediaKind: { type: String, required: true },
  title: { type: String, default: '' }
})

const PAGE_SIZE = 48
const COUNT_SAMPLE = 500

const activeTab = ref('mine')
const items = ref([])
const loading = ref(false)
const loadingMore = ref(false)
const hasMore = ref(true)
const beforeTime = ref(Date.now())
const counts = ref({ mine: 0, family: 0, my_share: 0 })
const countsHasMore = ref({ mine: false, family: false, my_share: false })

const scrollRef = ref(null)
const previewDialogVisible = ref(false)
const previewSrc = ref('')
const previewItem = ref(null)

const selectionMode = ref(false)
const selectedPaths = ref(new Set())
const shareLoading = ref(false)
const deleteLoading = ref(false)
const downloadLoading = ref(false)
const trashRetentionDays = ref(30)

const canManageShare = computed(() => activeTab.value === 'mine' || activeTab.value === 'my_share')
const canDelete = computed(() => activeTab.value === 'mine' || activeTab.value === 'my_share')
const canDownload = computed(() => activeTab.value === 'mine' || activeTab.value === 'my_share')

function tabLabel(tab) {
  const names = { mine: '我的', family: '家庭', my_share: '我的分享' }
  const c = counts.value[tab]
  const more = countsHasMore.value[tab] ? '+' : ''
  return `${names[tab]} (${c}${more})`
}

function matchesPath(path) {
  if (!path) return false
  switch (props.mediaKind) {
    case 'PHOTO':
      return path.startsWith('photos/')
    case 'VIDEO':
      return path.startsWith('videos/')
    case 'AUDIO':
      return path.startsWith('audio/') || path.startsWith('uploads/audio/')
    default:
      return false
  }
}

function mediaOwnerId(raw) {
  if (activeTab.value !== 'family') return null
  const v = raw.ownerUserId
  return v != null && v !== '' ? String(v) : null
}

function isShared(raw) {
  return raw.share === 1 || raw.share === true
}

function toDisplayItem(raw) {
  const path = raw.relativePath || ''
  const name = path.split('/').pop() || path
  const ownerLabel = raw.ownerDisplayName || raw.ownerUsername || ''
  const ownerId = mediaOwnerId(raw)
  const shared = activeTab.value === 'my_share' ? true : isShared(raw)
  return {
    relativePath: path,
    name,
    ownerId,
    shared,
    ownerLabel: activeTab.value === 'family' ? ownerLabel : '',
    thumbUrl: props.mediaKind === 'PHOTO' || props.mediaKind === 'VIDEO'
      ? mediaUrl(path, ownerId, 'thumb')
      : null,
    capturedAt: raw.capturedAt
  }
}

function patchItem(relativePath, ownerId, patch) {
  const idx = items.value.findIndex(
    i => i.relativePath === relativePath && i.ownerId === ownerId
  )
  if (idx >= 0) {
    items.value[idx] = { ...items.value[idx], ...patch }
  }
}

function onThumbError(item) {
  patchItem(item.relativePath, item.ownerId, { thumbUrl: null })
}

function isSelected(item) {
  return selectedPaths.value.has(item.relativePath)
}

function toggleSelect(item) {
  setSelected(item, !isSelected(item))
}

function setSelected(item, checked) {
  const next = new Set(selectedPaths.value)
  if (checked) next.add(item.relativePath)
  else next.delete(item.relativePath)
  selectedPaths.value = next
}

function selectAll() {
  selectedPaths.value = new Set(items.value.map(i => i.relativePath))
}

function clearSelection() {
  selectedPaths.value = new Set()
}

function toggleSelectionMode() {
  selectionMode.value = !selectionMode.value
  if (!selectionMode.value) clearSelection()
}

function onCardClick(item) {
  if (selectionMode.value && canManageShare.value) {
    toggleSelect(item)
    return
  }
  openPreview(item)
}

async function loadCounts() {
  try {
    const sync = await fetchSyncIndex()
    const list = (sync.data || []).filter(i => matchesPath(i.relativePath))
    counts.value.mine = list.length

    const familyRes = await fetchSharedMedia('family', props.mediaKind, Date.now(), COUNT_SAMPLE)
    const familyItems = (familyRes.data?.items || []).filter(i => matchesPath(i.relativePath))
    counts.value.family = familyItems.length
    countsHasMore.value.family = familyRes.data?.hasMore

    const shareRes = await fetchSharedMedia('mine', props.mediaKind, Date.now(), COUNT_SAMPLE)
    const shareItems = (shareRes.data?.items || []).filter(i => matchesPath(i.relativePath))
    counts.value.my_share = shareItems.length
    countsHasMore.value.my_share = shareRes.data?.hasMore
  } catch {
    /* counts optional */
  }
}

function resetList() {
  items.value = []
  hasMore.value = true
  beforeTime.value = Date.now()
  selectionMode.value = false
  clearSelection()
}

async function loadPage(append) {
  if (!append) loading.value = true
  else loadingMore.value = true

  try {
    let pageItems = []
    let more = false

    if (activeTab.value === 'mine') {
      const res = await fetchAlbumPhotos(props.mediaKind, beforeTime.value, PAGE_SIZE)
      const list = (res.data?.items || []).filter(i => matchesPath(i.relativePath))
      pageItems = list
      more = res.data?.hasMore ?? false
      if (list.length > 0) {
        const tail = list[list.length - 1]
        if (tail.capturedAt) beforeTime.value = tail.capturedAt
      }
    } else {
      const scope = activeTab.value === 'family' ? 'family' : 'mine'
      const res = await fetchSharedMedia(scope, props.mediaKind, beforeTime.value, PAGE_SIZE)
      const list = (res.data?.items || []).filter(i => matchesPath(i.relativePath))
      pageItems = list
      more = res.data?.hasMore ?? false
      if (list.length > 0) {
        const tail = list[list.length - 1]
        if (tail.capturedAt) beforeTime.value = tail.capturedAt
      }
    }

    const display = pageItems.map(toDisplayItem)
    if (append) items.value.push(...display)
    else items.value = display
    hasMore.value = more
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function onTabChange() {
  resetList()
  loadPage(false)
}

function onScroll(e) {
  const el = e.target
  if (loadingMore.value || loading.value || !hasMore.value) return
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 120) {
    loadPage(true)
  }
}

function openPreview(item) {
  previewItem.value = item
  previewSrc.value = mediaUrl(item.relativePath, item.ownerId, 'raw')
  previewDialogVisible.value = true
}

function onPreviewClosed() {
  previewSrc.value = ''
  previewItem.value = null
}

async function applyShare(paths, share) {
  if (!paths.length) {
    ElMessage.warning('请先选择文件')
    return
  }
  shareLoading.value = true
  try {
    const res = await updateShare(paths, share)
    const updated = res.data?.updated ?? 0
    if (updated === 0) {
      ElMessage.warning('没有可更新的文件（请确认已同步到云端）')
      return
    }
    ElMessage.success(share === 1 ? `已分享 ${updated} 项` : `已取消分享 ${updated} 项`)
    clearSelection()
    selectionMode.value = false
    previewDialogVisible.value = false
    await loadCounts()
    resetList()
    await loadPage(false)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    shareLoading.value = false
  }
}

function shareSelected(share) {
  applyShare([...selectedPaths.value], share)
}

function shareOne(item, share) {
  applyShare([item.relativePath], share)
}

async function deletePaths(paths) {
  if (!paths.length) {
    ElMessage.warning('请先选择文件')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定将 ${paths.length} 项移入回收站？\n\n${trashRetentionDays.value} 天内可在回收站恢复。`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  deleteLoading.value = true
  try {
    for (const path of paths) {
      await deleteFile(path)
    }
    ElMessage.success('已移入回收站')
    clearSelection()
    selectionMode.value = false
    previewDialogVisible.value = false
    await loadCounts()
    resetList()
    await loadPage(false)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    deleteLoading.value = false
  }
}

function deleteSelected() {
  deletePaths([...selectedPaths.value])
}

function deleteOne(item) {
  deletePaths([item.relativePath])
}

function downloadItem(item) {
  if (!item?.relativePath) return
  const token = localStorage.getItem('token')
  let url = `/media/download?path=${encodeURIComponent(item.relativePath)}`
  if (item.ownerId != null && item.ownerId !== '') {
    url += `&ownerId=${encodeURIComponent(String(item.ownerId))}`
  }
  if (token) url += `&token=${encodeURIComponent(token)}`
  const a = document.createElement('a')
  a.href = url
  a.download = item.name || 'download'
  a.click()
}

async function downloadPaths(paths) {
  if (!paths.length) {
    ElMessage.warning('请先选择文件')
    return
  }
  downloadLoading.value = true
  try {
    for (const path of paths) {
      const item = items.value.find(i => i.relativePath === path)
      if (item) downloadItem(item)
    }
    ElMessage.success(`已开始下载 ${paths.length} 项`)
    clearSelection()
    selectionMode.value = false
  } finally {
    downloadLoading.value = false
  }
}

function downloadSelected() {
  downloadPaths([...selectedPaths.value])
}

function downloadOne(item) {
  downloadPaths([item.relativePath])
}

async function shareAll(share) {
  const action = share === 1 ? '全部分享到家庭' : '取消全部分享'
  try {
    await ElMessageBox.confirm(`确定${action}？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  shareLoading.value = true
  try {
    const res = await updateShareAll(props.mediaKind, share)
    const updated = res.data?.updated ?? 0
    ElMessage.success(share === 1 ? `已分享 ${updated} 项` : `已取消分享 ${updated} 项`)
    await loadCounts()
    resetList()
    await loadPage(false)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    shareLoading.value = false
  }
}

onMounted(async () => {
  try {
    const cfg = await fetchConfig()
    trashRetentionDays.value = cfg.data?.trashRetentionDays ?? 30
  } catch {
    /* optional */
  }
  loadCounts()
  loadPage(false)
})

watch(() => props.mediaKind, () => {
  activeTab.value = 'mine'
  resetList()
  loadCounts()
  loadPage(false)
})
</script>

<style scoped>
.media-gallery {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  min-height: 480px;
}
.share-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}
.sel-hint {
  font-size: 12px;
  color: #909399;
  margin-left: 4px;
}
.grid-wrap {
  max-height: calc(100vh - 250px);
  overflow-y: auto;
  margin-top: 8px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 12px;
}
.card {
  cursor: pointer;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  overflow: hidden;
  transition: box-shadow 0.2s;
  background: #fafafa;
  position: relative;
}
.card.selectable:hover {
  border-color: #409eff;
}
.card.selected {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.25);
}
.card:hover {
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}
.sel-check {
  position: absolute;
  top: 6px;
  left: 6px;
  z-index: 2;
  background: rgba(255, 255, 255, 0.9);
  border-radius: 4px;
  padding: 0 2px;
}
.share-badge {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 2;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(64, 158, 255, 0.9);
  color: #fff;
}
.thumb {
  aspect-ratio: 1;
  background: #f0f2f5;
  display: flex;
  align-items: center;
  justify-content: center;
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.thumb-placeholder {
  font-size: 36px;
}
.thumb-loading {
  font-size: 12px;
  color: #909399;
}
.name {
  padding: 6px 8px;
  font-size: 12px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.owner {
  padding: 0 8px 6px;
  font-size: 11px;
  color: #909399;
}
.empty {
  text-align: center;
  padding: 48px;
  color: #909399;
}
.loading-more {
  text-align: center;
  padding: 12px;
  color: #909399;
}
.preview-body {
  min-height: 200px;
}
.preview-image {
  max-width: 100%;
  max-height: 70vh;
  display: block;
  margin: 0 auto;
}
.preview-video {
  width: 100%;
  max-height: 480px;
  background: #000;
}
.preview-audio {
  width: 100%;
}
</style>
