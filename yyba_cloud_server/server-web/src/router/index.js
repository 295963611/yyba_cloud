import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import Setup from '../views/Setup.vue'
import MainLayout from '../layouts/MainLayout.vue'
import MediaGallery from '../views/MediaGallery.vue'
import Files from '../views/Files.vue'
import Settings from '../views/Settings.vue'
import Users from '../views/Users.vue'
import { syncRoleFromServer, isAdminRole } from '../utils/auth'
import { ensureSetupStatus } from '../utils/setup'
import { getSetupStatus } from '../api'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/setup', component: Setup },
    { path: '/login', component: Login },
    {
      path: '/',
      component: MainLayout,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/album' },
        {
          path: 'album',
          component: MediaGallery,
          props: { mediaKind: 'PHOTO', title: '我的相册' }
        },
        {
          path: 'video',
          component: MediaGallery,
          props: { mediaKind: 'VIDEO', title: '我的视频' }
        },
        {
          path: 'audio',
          component: MediaGallery,
          props: { mediaKind: 'AUDIO', title: '我的音频' }
        },
        { path: 'files', component: Files },
        { path: 'settings', component: Settings, meta: { requiresAdmin: true } },
        { path: 'users', component: Users, meta: { requiresAdmin: true } }
      ]
    }
  ]
})

router.beforeEach(async (to, from, next) => {
  const usersExist = await ensureSetupStatus(getSetupStatus)

  if (!usersExist) {
    if (to.path === '/setup') {
      next()
    } else {
      next('/setup')
    }
    return
  }

  if (to.path === '/setup') {
    next('/login')
    return
  }

  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/album')
  } else if (to.meta.requiresAdmin) {
    if (isAdminRole()) {
      next()
      return
    }
    await syncRoleFromServer()
    if (isAdminRole()) {
      next()
    } else {
      next('/album')
    }
  } else {
    next()
  }
})

export default router
