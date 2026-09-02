import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

api.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  res => {
    const data = res.data
    if (data.code !== 200) {
      return Promise.reject(new Error(data.message || '请求失败'))
    }
    return data
  },
  err => Promise.reject(new Error(err.response?.data?.message || err.message))
)

export function login(username, password) {
  return api.post('/auth/login', { username, password })
}

export function getSetupStatus() {
  return api.get('/auth/setup-status')
}

export function setupAccount(body) {
  return api.post('/auth/setup', body)
}

export function browseSetupDirectories(path) {
  const params = {}
  if (path) params.path = path
  return api.get('/auth/setup/browse', { params })
}

export function listUsers() {
  return api.get('/users')
}

export function createUser(body) {
  return api.post('/users', body)
}

export function setUserEnabled(id, enabled) {
  return api.put(`/users/${id}/enabled`, { enabled })
}

export function resetUserPassword(id, password) {
  return api.put(`/users/${id}/password`, { password })
}

export function listFiles(path = '') {
  return api.get('/files', { params: { path } })
}

export function deleteFile(path) {
  return api.delete('/files', { params: { path } })
}

export function fetchTrash() {
  return api.get('/files/trash')
}

export function restoreFile(path) {
  return api.put('/files/restore', null, { params: { path } })
}

export function permanentDeleteFromTrash(path) {
  return api.delete('/files/trash', { params: { path } })
}

export function fetchConfig() {
  return api.get('/config')
}

export function fetchAlbumPhotos(fileType, beforeTime, limit = 48) {
  return api.get('/files/album-photos', {
    params: { fileType, beforeTime, limit }
  })
}

export function fetchSharedMedia(scope, fileType, beforeTime, limit = 48) {
  return api.get('/files/shared', {
    params: { scope, fileType, beforeTime, limit }
  })
}

export function fetchSyncIndex(since) {
  const params = {}
  if (since != null && since > 0) {
    params.since = since
  }
  return api.get('/files/sync-index', { params }).then(res => {
    const payload = res.data
    const items = Array.isArray(payload) ? payload : (payload?.items || [])
    return { ...res, data: items }
  })
}

export function updateShare(paths, share) {
  return api.put('/files/share', { paths, share })
}

export function updateShareAll(fileType, share) {
  return api.put('/files/share/all', null, { params: { fileType, share } })
}

export function getConfig() {
  return api.get('/config')
}

export function updateConfig(body) {
  return api.put('/config', body)
}

function appendOwnerId(url, ownerId) {
  if (ownerId != null && ownerId !== '' && ownerId !== 'undefined') {
    return `${url}&ownerId=${encodeURIComponent(String(ownerId))}`
  }
  return url
}

/**
 * 网盘资源静态直链 URL（/media/file），供网页 img/video/iframe 直接使用。
 */
export function fileUrl(path, ownerId) {
  const token = localStorage.getItem('token')
  let url = `/media/file?path=${encodeURIComponent(path)}`
  url = appendOwnerId(url, ownerId)
  if (token) url += `&token=${encodeURIComponent(token)}`
  return url
}

/**
 * 媒体直链 URL（与 Web 同端口 /media），供 img/video/audio 标签直接使用。
 * @param {string} path 相对路径
 * @param {string|number|undefined} ownerId 家庭分享时的文件所有者（须为字符串，避免大整数精度丢失）
 * @param {'thumb'|'raw'|'file'} kind 缩略图或原文件
 */
export function mediaUrl(path, ownerId, kind = 'raw') {
  if (kind === 'file') {
    return fileUrl(path, ownerId)
  }
  const token = localStorage.getItem('token')
  const endpoint = kind === 'thumb' ? 'thumb' : 'raw'
  let url = `/media/${endpoint}?path=${encodeURIComponent(path)}`
  url = appendOwnerId(url, ownerId)
  if (token) url += `&token=${encodeURIComponent(token)}`
  return url
}

export function authFetch(path, ownerId, kind = 'download') {
  const token = localStorage.getItem('token')
  const endpoint = kind === 'thumbnail' ? '/api/files/thumbnail' : '/api/files/download'
  let url = `${endpoint}?path=${encodeURIComponent(path)}`
  url = appendOwnerId(url, ownerId)
  return fetch(url, { headers: { Authorization: `Bearer ${token}` } })
}

export async function uploadFile(file, relativePath, onProgress) {
  const chunkSize = 20 * 1024 * 1024
  const totalSize = file.size
  const initRes = await api.post('/upload/init', {
    fileName: file.name,
    relativePath,
    totalSize,
    mimeType: file.type,
    source: 'WEB_UPLOAD'
  })
  const { uploadId, chunkSize: serverChunkSize, totalChunks, uploadedChunks } = initRes.data
  const size = serverChunkSize || chunkSize
  const uploaded = new Set(uploadedChunks || [])
  let done = uploaded.size
  if (onProgress) onProgress(Math.round((done / totalChunks) * 100))

  for (let i = 0; i < totalChunks; i++) {
    if (uploaded.has(i)) continue
    const start = i * size
    const end = Math.min(start + size, totalSize)
    const blob = file.slice(start, end)
    await api.put(`/upload/${uploadId}/chunk/${i}`, blob, {
      headers: { 'Content-Type': 'application/octet-stream' },
      onUploadProgress: e => {
        if (!onProgress || !e.total) return
        const chunkRatio = e.loaded / e.total
        const percent = Math.round(((done + chunkRatio) / totalChunks) * 100)
        onProgress(Math.min(percent, 99))
      }
    })
    done++
    if (onProgress) onProgress(Math.round((done / totalChunks) * 100))
  }

  return api.post(`/upload/${uploadId}/complete`)
}

export default api
