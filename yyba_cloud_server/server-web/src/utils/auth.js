import { getConfig } from '../api'

/** 从服务端同步当前用户角色到 localStorage */
export async function syncRoleFromServer() {
  try {
    const res = await getConfig()
    const admin = Boolean(res.data?.admin)
    const role = admin ? 'ADMIN' : 'MEMBER'
    localStorage.setItem('role', role)
    return role
  } catch {
    return localStorage.getItem('role') || 'MEMBER'
  }
}

export function isAdminRole() {
  return localStorage.getItem('role') === 'ADMIN'
}
