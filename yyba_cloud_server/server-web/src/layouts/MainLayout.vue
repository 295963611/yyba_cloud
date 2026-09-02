<template>
  <el-container class="main-layout">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <img :src="logoUrl('sm')" alt="" class="brand-logo" />
        <span>悠悠爸家庭云盘</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        class="side-menu"
        background-color="#1f2d3d"
        text-color="#bfcbd9"
        active-text-color="#409eff"
      >
        <el-sub-menu index="cloud">
          <template #title>
            <span>我的云盘</span>
          </template>
          <el-menu-item index="/album">我的相册</el-menu-item>
          <el-menu-item index="/video">我的视频</el-menu-item>
          <el-menu-item index="/audio">我的音频</el-menu-item>
          <el-menu-item index="/files">网盘内容</el-menu-item>
          <el-menu-item v-if="isAdmin" index="/users">用户管理</el-menu-item>
          <el-menu-item v-if="isAdmin" index="/settings">网盘设置</el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ pageTitle }}</span>
        <div class="header-right">
          <span class="username">{{ username }}</span>
          <el-button type="danger" link @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { syncRoleFromServer } from '../utils/auth'
import { logoUrl } from '../utils/logo'

const route = useRoute()
const router = useRouter()
const username = localStorage.getItem('username') || ''
const isAdmin = ref(localStorage.getItem('role') === 'ADMIN')

onMounted(async () => {
  const role = await syncRoleFromServer()
  isAdmin.value = role === 'ADMIN'
})

const activeMenu = computed(() => route.path)

const pageTitle = computed(() => {
  const map = {
    '/album': '我的相册',
    '/video': '我的视频',
    '/audio': '我的音频',
    '/files': '网盘内容',
    '/users': '用户管理',
    '/settings': '网盘设置'
  }
  return map[route.path] || '我的云盘'
})

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('role')
  localStorage.removeItem('userId')
  router.push('/login')
}
</script>

<style scoped>
.main-layout {
  min-height: 100vh;
}
.aside {
  background: #1f2d3d;
  color: #fff;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 16px;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  border-bottom: 1px solid #2d3a4b;
}
.brand-logo {
  width: 32px;
  height: 32px;
  object-fit: contain;
  flex-shrink: 0;
}
.side-menu {
  border-right: none;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  padding: 0 20px;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.username {
  color: #606266;
}
.main {
  background: #f5f7fa;
  min-height: calc(100vh - 60px);
}
</style>
