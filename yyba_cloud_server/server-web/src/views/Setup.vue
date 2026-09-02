<template>
  <div class="setup-page">
    <el-card class="setup-card">
      <div class="hero">
        <img :src="logoUrl('xl')" alt="悠悠爸家庭云盘" class="hero-logo" />
        <h1>欢迎使用悠悠爸家庭云盘</h1>
        <p class="subtitle">首次使用，请完成初始化设置</p>
      </div>

      <el-steps :active="step" align-center finish-status="success" class="steps">
        <el-step title="欢迎" />
        <el-step title="存储目录" />
        <el-step title="管理员" />
      </el-steps>

      <div v-if="step === 0" class="step-body">
        <p>这是您第一次访问家庭云盘。初始化需要完成：</p>
        <ul class="feature-list">
          <li>选择主存储目录（所有文件将保存在此）</li>
          <li>创建管理员账号（管理家庭成员与网盘设置）</li>
        </ul>
        <el-button type="primary" size="large" style="width:100%" @click="step = 1">
          开始设置
        </el-button>
      </div>

      <div v-else-if="step === 1" class="step-body">
        <el-form :model="form" label-width="100px" @submit.prevent>
          <el-form-item label="主存储目录" required>
            <div class="path-picker">
              <el-input
                v-model="form.storageRootPath"
                readonly
                placeholder="请点击选择文件夹"
              />
              <label class="folder-select-label">
                <input
                  type="file"
                  class="folder-input"
                  webkitdirectory
                  directory
                  @click.prevent="openFolderPicker"
                  @change.prevent
                />
                <span class="folder-select-btn">选择文件夹</span>
              </label>
            </div>
            <div class="hint">
              将浏览服务器本机磁盘目录，请选择一个空间充足、可写的文件夹。
            </div>
          </el-form-item>
          <el-button type="primary" size="large" style="width:100%" @click="goToAccountStep">
            下一步
          </el-button>
          <el-button style="width:100%; margin-top: 12px; margin-left: 0" @click="step = 0">
            返回
          </el-button>
        </el-form>
      </div>

      <div v-else-if="step === 2" class="step-body">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="storage-hint"
          :title="`存储目录：${form.storageRootPath}`"
        />
        <el-form :model="form" label-width="90px" @submit.prevent>
          <el-form-item label="用户名" required>
            <el-input v-model="form.username" placeholder="至少 2 个字符，用于登录" />
          </el-form-item>
          <el-form-item label="显示名称">
            <el-input v-model="form.displayName" placeholder="可选，默认同用户名" />
          </el-form-item>
          <el-form-item label="密码" required>
            <el-input v-model="form.password" type="password" placeholder="至少 6 个字符" show-password />
          </el-form-item>
          <el-form-item label="确认密码" required>
            <el-input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" show-password />
          </el-form-item>
          <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="handleSetup">
            完成设置
          </el-button>
          <el-button style="width:100%; margin-top: 12px; margin-left: 0" @click="step = 1">
            返回
          </el-button>
        </el-form>
      </div>
    </el-card>

    <el-dialog v-model="pickerVisible" title="选择存储文件夹" width="560px" @open="loadBrowse('')">
      <div class="browse-toolbar">
        <el-button :disabled="browseParent === null" @click="loadBrowse(browseParent)">
          上级目录
        </el-button>
        <span class="browse-current">{{ browseCurrent || '此电脑' }}</span>
      </div>
      <div v-loading="browseLoading" class="browse-list">
        <div
          v-for="item in browseEntries"
          :key="item.path"
          class="browse-item"
          :class="{ active: item.path === browseCurrent }"
          @click="selectBrowseItem(item)"
          @dblclick="enterBrowseItem(item)"
        >
          <span class="folder-icon">📁</span>
          <span class="folder-name">{{ item.name }}</span>
        </div>
        <el-empty v-if="!browseLoading && browseEntries.length === 0" description="此目录下没有子文件夹" />
      </div>
      <template #footer>
        <el-button @click="pickerVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!browseCurrent" @click="confirmFolder">
          选择此文件夹
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { browseSetupDirectories, getSetupStatus, setupAccount } from '../api'
import { markSetupComplete } from '../utils/setup'
import { logoUrl } from '../utils/logo'

const router = useRouter()
const step = ref(0)
const loading = ref(false)
const pickerVisible = ref(false)
const browseLoading = ref(false)
const browseCurrent = ref('')
const browseParent = ref(null)
const browseEntries = ref([])
const form = reactive({
  storageRootPath: '',
  username: '',
  displayName: '',
  password: '',
  confirmPassword: ''
})

onMounted(async () => {
  try {
    const res = await getSetupStatus()
    if (res.data?.storageRootPath) {
      form.storageRootPath = res.data.storageRootPath
    }
  } catch {
    // ignore
  }
})

function openFolderPicker() {
  pickerVisible.value = true
}

async function loadBrowse(path) {
  browseLoading.value = true
  try {
    const res = await browseSetupDirectories(path || undefined)
    const data = res.data || {}
    browseCurrent.value = data.current || ''
    browseParent.value = data.parent
    browseEntries.value = data.entries || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    browseLoading.value = false
  }
}

function selectBrowseItem(item) {
  browseCurrent.value = item.path
}

function enterBrowseItem(item) {
  loadBrowse(item.path)
}

function confirmFolder() {
  if (!browseCurrent.value) {
    ElMessage.warning('请选择一个文件夹')
    return
  }
  form.storageRootPath = browseCurrent.value
  pickerVisible.value = false
}

function goToAccountStep() {
  const path = form.storageRootPath?.trim()
  if (!path) {
    ElMessage.warning('请选择主存储目录')
    return
  }
  step.value = 2
}

async function handleSetup() {
  const storagePath = form.storageRootPath?.trim()
  if (!storagePath) {
    ElMessage.warning('请选择主存储目录')
    step.value = 1
    return
  }
  if (!form.username || form.username.length < 2) {
    ElMessage.warning('用户名至少 2 个字符')
    return
  }
  if (!form.password || form.password.length < 6) {
    ElMessage.warning('密码至少 6 个字符')
    return
  }
  if (form.password !== form.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    const res = await setupAccount({
      storageRootPath: storagePath,
      username: form.username,
      password: form.password,
      displayName: form.displayName || undefined
    })
    localStorage.setItem('token', res.data.token)
    localStorage.setItem('username', res.data.username)
    if (res.data.userId != null) {
      localStorage.setItem('userId', String(res.data.userId))
    }
    localStorage.setItem('role', res.data.role || 'ADMIN')
    markSetupComplete()
    ElMessage.success('初始化完成')
    router.push('/album')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.setup-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 24px;
}
.setup-card {
  width: 520px;
  padding: 28px;
}
.hero {
  text-align: center;
  margin-bottom: 24px;
}
.hero-logo {
  width: 96px;
  height: 96px;
  object-fit: contain;
  margin-bottom: 12px;
}
.hero h1 {
  margin: 0 0 8px;
  font-size: 22px;
}
.subtitle {
  margin: 0;
  color: #909399;
  font-size: 14px;
}
.steps {
  margin-bottom: 28px;
}
.step-body {
  margin-top: 8px;
}
.feature-list {
  margin: 16px 0 24px;
  padding-left: 20px;
  color: #606266;
  line-height: 1.8;
}
.path-picker {
  display: flex;
  gap: 8px;
  width: 100%;
}
.path-picker .el-input {
  flex: 1;
}
.folder-select-label {
  display: inline-flex;
  flex-shrink: 0;
  cursor: pointer;
}
.folder-input {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  pointer-events: none;
}
.folder-select-btn {
  display: inline-flex;
  align-items: center;
  height: 32px;
  padding: 0 15px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  color: #606266;
  font-size: 14px;
  white-space: nowrap;
}
.folder-select-btn:hover {
  color: #409eff;
  border-color: #c6e2ff;
  background: #ecf5ff;
}
.hint {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}
.storage-hint {
  margin-bottom: 16px;
}
.browse-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.browse-current {
  flex: 1;
  font-size: 13px;
  color: #606266;
  word-break: break-all;
}
.browse-list {
  min-height: 280px;
  max-height: 360px;
  overflow-y: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.browse-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  cursor: pointer;
  border-bottom: 1px solid #f2f6fc;
}
.browse-item:hover,
.browse-item.active {
  background: #ecf5ff;
}
.folder-icon {
  flex-shrink: 0;
}
.folder-name {
  word-break: break-all;
}
</style>
