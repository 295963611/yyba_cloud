<template>
  <div class="files-page">
    <div class="toolbar">
      <el-breadcrumb v-if="viewMode === 'files'" separator="/">
        <el-breadcrumb-item>
          <a href="#" @click.prevent="navigate('')">根目录</a>
        </el-breadcrumb-item>
        <el-breadcrumb-item v-for="(seg, i) in pathSegments" :key="i">
          <a href="#" @click.prevent="navigate(pathSegments.slice(0, i + 1).join('/'))">{{ seg }}</a>
        </el-breadcrumb-item>
      </el-breadcrumb>
      <span v-else class="trash-title">回收站</span>
      <div class="actions">
        <el-button v-if="viewMode === 'files'" @click="openTrash">回收站</el-button>
        <el-button v-else @click="backToFiles">返回网盘</el-button>
        <el-upload v-if="viewMode === 'files'" :show-file-list="false" :auto-upload="false" :on-change="onFileSelect" multiple>
          <el-button type="primary">上传文件</el-button>
        </el-upload>
        <el-button @click="viewMode === 'files' ? loadFiles() : loadTrash()">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="viewMode === 'trash'"
      type="info"
      :closable="false"
      show-icon
      class="trash-hint"
      :title="`删除的文件保留 ${trashRetentionDays} 天，期内可恢复；超期将自动永久删除。`"
    />

    <el-table v-if="viewMode === 'files'" :data="files" v-loading="loading" stripe>
      <el-table-column label="预览" width="72">
        <template #default="{ row }">
          <div v-if="!row.directory && isImage(row)" class="mini-thumb">
            <img
              :src="thumbUrl(row)"
              alt=""
              loading="lazy"
              @error="e => onThumbError(e, row)"
            />
          </div>
          <span v-else-if="!row.directory" class="mini-icon">{{ fileIcon(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="名称" min-width="240">
        <template #default="{ row }">
          <el-link v-if="row.directory" type="primary" @click="enterDir(row)">
            📁 {{ row.name }}
          </el-link>
          <el-link
            v-else-if="canPreview(row)"
            type="primary"
            @click="openPreview(row)"
          >
            {{ row.name }}
          </el-link>
          <span v-else>{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="120">
        <template #default="{ row }">
          {{ row.directory ? '-' : formatSize(row.size) }}
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100" prop="fileType" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <template v-if="!row.directory">
            <el-button v-if="canPreview(row)" link type="primary" @click="openPreview(row)">预览</el-button>
            <el-button link type="primary" @click="download(row)">下载</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-table v-else :data="trashFiles" v-loading="trashLoading" stripe>
      <el-table-column label="名称" min-width="280" prop="name" />
      <el-table-column label="大小" width="120">
        <template #default="{ row }">
          {{ formatSize(row.size) }}
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100" prop="fileType" />
      <el-table-column label="删除时间" width="180" prop="deletedAt" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="restore(row)">恢复</el-button>
          <el-button link type="danger" @click="permanentDelete(row)">永久删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="previewVisible"
      :title="previewItem?.name || '预览'"
      width="860px"
      destroy-on-close
      @closed="previewItem = null"
    >
      <div v-if="previewItem" class="preview-wrap">
        <img
          v-if="previewKind === 'image'"
          :src="previewSrc"
          alt=""
          class="preview-image"
        />
        <video
          v-else-if="previewKind === 'video'"
          :src="previewSrc"
          controls
          autoplay
          class="preview-video"
        />
        <audio
          v-else-if="previewKind === 'audio'"
          :src="previewSrc"
          controls
          autoplay
          class="preview-audio"
        />
        <iframe
          v-else-if="previewKind === 'pdf'"
          :src="previewSrc"
          class="preview-pdf"
          title="PDF 预览"
        />
        <pre v-else-if="previewKind === 'text'" class="preview-text">{{ textContent }}</pre>
        <div v-else class="preview-fallback">
          该类型暂不支持在线预览，请下载后查看。
          <el-button type="primary" link @click="download(previewItem)">下载文件</el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="uploadVisible" title="上传进度" width="400px" :close-on-click-modal="false">
      <div v-for="item in uploadList" :key="item.id" class="upload-item">
        <span>{{ item.name }}</span>
        <el-progress :percentage="item.progress" :status="item.status || undefined" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listFiles, deleteFile, fetchTrash, restoreFile, permanentDeleteFromTrash, fetchConfig, uploadFile as doUpload, fileUrl, mediaUrl } from '../api'

const currentPath = ref('')
const files = ref([])
const loading = ref(false)
const viewMode = ref('files')
const trashFiles = ref([])
const trashLoading = ref(false)
const uploadVisible = ref(false)
const uploadList = ref([])
const trashRetentionDays = ref(30)

const previewVisible = ref(false)
const previewItem = ref(null)
const previewSrc = ref('')
const previewKind = ref('')
const textContent = ref('')

const pathSegments = computed(() =>
  currentPath.value ? currentPath.value.split('/').filter(Boolean) : []
)

onMounted(async () => {
  try {
    const cfg = await fetchConfig()
    trashRetentionDays.value = cfg.data?.trashRetentionDays ?? 30
  } catch {
    /* optional */
  }
  loadFiles()
})

async function loadFiles() {
  loading.value = true
  try {
    const res = await listFiles(currentPath.value)
    files.value = res.data || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function navigate(path) {
  currentPath.value = path
  loadFiles()
}

function openTrash() {
  viewMode.value = 'trash'
  loadTrash()
}

function backToFiles() {
  viewMode.value = 'files'
  loadFiles()
}

async function loadTrash() {
  trashLoading.value = true
  try {
    const res = await fetchTrash()
    trashFiles.value = res.data || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    trashLoading.value = false
  }
}

async function restore(row) {
  try {
    await restoreFile(row.relativePath)
    ElMessage.success('已恢复')
    loadTrash()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function enterDir(row) {
  currentPath.value = row.relativePath
  loadFiles()
}

function extOf(name) {
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : ''
}

function isImage(row) {
  if (row.fileType === 'PHOTO') return true
  return ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'].includes(extOf(row.name))
}

function canPreview(row) {
  const ext = extOf(row.name)
  if (row.fileType === 'PHOTO' || row.fileType === 'VIDEO') return true
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'].includes(ext)) return true
  if (['mp4', 'webm', 'mov', 'mkv', '3gp'].includes(ext)) return true
  if (['mp3', 'm4a', 'wav', 'ogg', 'flac', 'aac'].includes(ext)) return true
  if (ext === 'pdf') return true
  if (['txt', 'json', 'log', 'md', 'xml', 'csv'].includes(ext)) return true
  return false
}

function previewType(row) {
  const ext = extOf(row.name)
  if (isImage(row)) return 'image'
  if (row.fileType === 'VIDEO' || ['mp4', 'webm', 'mov', 'mkv', '3gp'].includes(ext)) return 'video'
  if (['mp3', 'm4a', 'wav', 'ogg', 'flac', 'aac'].includes(ext)) return 'audio'
  if (ext === 'pdf') return 'pdf'
  if (['txt', 'json', 'log', 'md', 'xml', 'csv'].includes(ext)) return 'text'
  return 'other'
}

function thumbUrl(row) {
  return mediaUrl(row.relativePath, null, 'thumb')
}

function onThumbError(e, row) {
  const img = e.target
  if (img && row) {
    img.src = fileUrl(row.relativePath)
  }
}

function fileIcon(row) {
  if (row.fileType === 'PHOTO' || isImage(row)) return '🖼️'
  if (row.fileType === 'VIDEO') return '🎬'
  if (previewType(row) === 'audio') return '🎵'
  if (extOf(row.name) === 'pdf') return '📕'
  return '📄'
}

function formatSize(size) {
  if (!size) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let s = size
  while (s >= 1024 && i < units.length - 1) { s /= 1024; i++ }
  return `${s.toFixed(1)} ${units[i]}`
}

async function openPreview(row) {
  previewItem.value = row
  previewSrc.value = fileUrl(row.relativePath)
  previewKind.value = previewType(row)
  textContent.value = ''
  previewVisible.value = true
  if (previewKind.value === 'text') {
    try {
      const token = localStorage.getItem('token')
      const res = await fetch(previewSrc.value, {
        headers: token ? { Authorization: `Bearer ${token}` } : {}
      })
      if (!res.ok) throw new Error('加载失败')
      textContent.value = await res.text()
    } catch {
      textContent.value = '文本加载失败'
    }
  }
}

function download(row) {
  const token = localStorage.getItem('token')
  const url = `/media/download?path=${encodeURIComponent(row.relativePath)}&token=${encodeURIComponent(token || '')}`
  const a = document.createElement('a')
  a.href = url
  a.download = row.name
  a.click()
}

async function remove(row) {
  await ElMessageBox.confirm(
    `确定将 ${row.name} 移入回收站？\n\n${trashRetentionDays.value} 天内可在回收站恢复。`,
    '提示',
    { type: 'warning' }
  )
  try {
    await deleteFile(row.relativePath)
    ElMessage.success('已移入回收站')
    loadFiles()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function permanentDelete(row) {
  await ElMessageBox.confirm(
    `永久删除 ${row.name}？此操作不可恢复。`,
    '永久删除',
    { type: 'warning' }
  )
  try {
    await permanentDeleteFromTrash(row.relativePath)
    ElMessage.success('已永久删除')
    loadTrash()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function onFileSelect(fileItem) {
  const file = fileItem.raw
  if (!file) return
  uploadVisible.value = true
  const idx = uploadList.value.length
  uploadList.value.push({
    id: `${Date.now()}-${Math.random()}`,
    name: file.name,
    progress: 0,
    status: ''
  })
  const relativePath = currentPath.value
    ? `${currentPath.value}/uploads/${file.name}`
    : `uploads/${file.name}`
  try {
    await doUpload(file, relativePath, p => {
      uploadList.value[idx].progress = p
    })
    uploadList.value[idx].status = 'success'
    uploadList.value[idx].progress = 100
    ElMessage.success(`${file.name} 上传成功`)
    loadFiles()
  } catch (e) {
    uploadList.value[idx].status = 'exception'
    ElMessage.error(e.message)
  }
}
</script>

<style scoped>
.files-page {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.actions { display: flex; gap: 8px; }
.trash-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}
.trash-hint { margin-bottom: 12px; }
.upload-item { margin-bottom: 12px; }
.mini-thumb {
  width: 40px;
  height: 40px;
  border-radius: 4px;
  overflow: hidden;
  background: #f0f2f5;
}
.mini-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.mini-icon {
  font-size: 22px;
  line-height: 40px;
}
.preview-wrap {
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
.preview-pdf {
  width: 100%;
  height: 70vh;
  border: none;
}
.preview-text {
  max-height: 70vh;
  overflow: auto;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
}
.preview-fallback {
  text-align: center;
  padding: 48px;
  color: #909399;
}
</style>
