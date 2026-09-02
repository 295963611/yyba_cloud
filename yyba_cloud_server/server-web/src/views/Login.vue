<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-header">
        <img :src="logoUrl('lg')" alt="悠悠爸家庭云盘" class="login-logo" />
        <h2>悠悠爸家庭云盘</h2>
      </div>
      <el-form :model="form" @submit.prevent="handleLogin">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>
        <el-button type="primary" style="width:100%" :loading="loading" @click="handleLogin">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../api'
import { logoUrl } from '../utils/logo'

const router = useRouter()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function handleLogin() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const res = await login(form.username, form.password)
    localStorage.setItem('token', res.data.token)
    localStorage.setItem('username', res.data.username)
    if (res.data.userId != null) {
      localStorage.setItem('userId', String(res.data.userId))
    }
    if (res.data.role) {
      localStorage.setItem('role', res.data.role)
    } else {
      localStorage.setItem('role', 'MEMBER')
    }
    router.push('/album')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  width: 400px;
  padding: 20px;
}
.login-header {
  text-align: center;
  margin-bottom: 24px;
}
.login-logo {
  width: 72px;
  height: 72px;
  object-fit: contain;
  margin-bottom: 12px;
}
h2 {
  text-align: center;
  margin: 0;
}
</style>
