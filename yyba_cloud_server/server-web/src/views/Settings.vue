<template>
  <div class="settings-page">
    <el-alert
      v-if="!isAdmin"
      type="info"
      :closable="false"
      title="仅管理员可修改服务器配置，请联系管理员或在启动器中配置。"
      show-icon
      class="readonly-hint"
    />
    <el-form
      v-loading="loading"
      label-width="140px"
      class="form"
      :disabled="!isAdmin"
      @submit.prevent
    >
      <el-form-item label="服务端口">
        <el-input-number v-model="form.port" :min="1024" :max="65535" />
        <div class="hint">修改端口后需在启动器中重启服务生效</div>
      </el-form-item>
      <el-form-item label="主存储目录">
        <el-input v-model="form.storageRootPath" placeholder="例如 D:/yyba_storage" />
      </el-form-item>
      <el-form-item label="启用灾备">
        <el-switch v-model="form.backupEnabled" />
      </el-form-item>
      <el-form-item label="灾备目录">
        <el-input v-model="form.backupPath" placeholder="灾备镜像目录路径" />
      </el-form-item>
      <el-form-item label="上传分片大小">
        <el-select v-model="form.chunkSize" style="width: 200px">
          <el-option label="1 MB" :value="1048576" />
          <el-option label="4 MB" :value="4194304" />
          <el-option label="20 MB" :value="20971520" />
        </el-select>
      </el-form-item>
      <el-form-item label="回收站保留">
        <el-input-number v-model="form.trashRetentionDays" :min="1" :max="365" />
        <div class="hint">天，超期自动永久删除</div>
      </el-form-item>
      <el-form-item v-if="isAdmin">
        <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
        <el-button @click="load">刷新</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getConfig, updateConfig } from '../api'
import { syncRoleFromServer } from '../utils/auth'

const loading = ref(false)
const saving = ref(false)
const isAdmin = ref(localStorage.getItem('role') === 'ADMIN')
const form = reactive({
  port: 8080,
  storageRootPath: '',
  backupEnabled: false,
  backupPath: '',
  chunkSize: 20971520,
  trashRetentionDays: 30
})

async function load() {
  loading.value = true
  try {
    const role = await syncRoleFromServer()
    const res = await getConfig()
    const d = res.data || {}
    isAdmin.value = role === 'ADMIN' || Boolean(d.admin)
    form.port = d.port ?? 8080
    form.storageRootPath = d.storageRootPath || ''
    form.backupEnabled = Boolean(d.backupEnabled)
    form.backupPath = d.backupPath || ''
    form.chunkSize = d.chunkSize || 20971520
    form.trashRetentionDays = d.trashRetentionDays ?? 30
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!isAdmin.value) {
    ElMessage.warning('需要管理员权限')
    return
  }
  saving.value = true
  try {
    await updateConfig({
      port: form.port,
      storageRootPath: form.storageRootPath,
      backupEnabled: form.backupEnabled,
      backupPath: form.backupPath,
      chunkSize: form.chunkSize,
      trashRetentionDays: form.trashRetentionDays
    })
    ElMessage.success('设置已保存')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.settings-page {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
  max-width: 640px;
}
.readonly-hint {
  margin-bottom: 16px;
}
.hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
</style>
