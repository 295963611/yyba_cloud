<template>
  <div class="users-page">
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">添加账号</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="users" stripe>
      <el-table-column prop="username" label="用户名" min-width="120" />
      <el-table-column prop="displayName" label="显示名称" min-width="120" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">
            {{ row.role === 'ADMIN' ? '管理员' : '成员' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
            {{ row.enabled === 1 ? '正常' : '已禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="160" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="openResetPassword(row)">重置密码</el-button>
          <el-button
            v-if="row.enabled === 1"
            type="danger"
            link
            :disabled="isSelf(row)"
            @click="toggleEnabled(row, false)"
          >
            禁用
          </el-button>
          <el-button
            v-else
            type="primary"
            link
            @click="toggleEnabled(row, true)"
          >
            启用
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="添加账号" width="420px" @closed="resetCreateForm">
      <el-form :model="createForm" label-width="90px" @submit.prevent>
        <el-form-item label="用户名" required>
          <el-input v-model="createForm.username" placeholder="至少 2 个字符" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="createForm.displayName" placeholder="可选" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="createForm.password" type="password" placeholder="至少 6 个字符" show-password />
        </el-form-item>
        <el-form-item label="确认密码" required>
          <el-input v-model="createForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetVisible" title="重置密码" width="420px" @closed="resetPasswordForm">
      <p class="reset-hint">为用户「{{ resetTarget?.username }}」设置新密码</p>
      <el-form :model="resetForm" label-width="90px" @submit.prevent>
        <el-form-item label="新密码" required>
          <el-input v-model="resetForm.password" type="password" placeholder="至少 6 个字符" show-password />
        </el-form-item>
        <el-form-item label="确认密码" required>
          <el-input v-model="resetForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="handleResetPassword">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listUsers, createUser, setUserEnabled, resetUserPassword } from '../api'

const loading = ref(false)
const creating = ref(false)
const resetting = ref(false)
const users = ref([])
const createVisible = ref(false)
const resetVisible = ref(false)
const resetTarget = ref(null)
const currentUserId = localStorage.getItem('userId')
const createForm = reactive({
  username: '',
  displayName: '',
  password: '',
  confirmPassword: ''
})
const resetForm = reactive({
  password: '',
  confirmPassword: ''
})

function isSelf(row) {
  return String(row.id) === currentUserId
}

async function load() {
  loading.value = true
  try {
    const res = await listUsers()
    users.value = res.data || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createVisible.value = true
}

function resetCreateForm() {
  createForm.username = ''
  createForm.displayName = ''
  createForm.password = ''
  createForm.confirmPassword = ''
}

function openResetPassword(row) {
  resetTarget.value = row
  resetVisible.value = true
}

function resetPasswordForm() {
  resetTarget.value = null
  resetForm.password = ''
  resetForm.confirmPassword = ''
}

async function handleCreate() {
  if (!createForm.username || createForm.username.length < 2) {
    ElMessage.warning('用户名至少 2 个字符')
    return
  }
  if (!createForm.password || createForm.password.length < 6) {
    ElMessage.warning('密码至少 6 个字符')
    return
  }
  if (createForm.password !== createForm.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  creating.value = true
  try {
    await createUser({
      username: createForm.username,
      password: createForm.password,
      displayName: createForm.displayName || undefined
    })
    ElMessage.success('账号创建成功')
    createVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
}

async function handleResetPassword() {
  if (!resetForm.password || resetForm.password.length < 6) {
    ElMessage.warning('密码至少 6 个字符')
    return
  }
  if (resetForm.password !== resetForm.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  resetting.value = true
  try {
    await resetUserPassword(resetTarget.value.id, resetForm.password)
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    resetting.value = false
  }
}

async function toggleEnabled(row, enabled) {
  const action = enabled ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确定要${action}用户「${row.username}」吗？`, '确认', {
      type: 'warning'
    })
    await setUserEnabled(row.id, enabled)
    ElMessage.success(`已${action}`)
    await load()
  } catch (e) {
    if (e !== 'cancel' && e?.message) {
      ElMessage.error(e.message)
    }
  }
}

onMounted(load)
</script>

<style scoped>
.users-page {
  max-width: 960px;
}
.toolbar {
  margin-bottom: 16px;
  display: flex;
  gap: 8px;
}
.reset-hint {
  margin: 0 0 16px;
  color: #606266;
}
</style>
