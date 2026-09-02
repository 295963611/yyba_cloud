let setupChecked = false
let hasUsers = true

export async function ensureSetupStatus(fetchStatus) {
  if (setupChecked) return hasUsers
  try {
    const res = await fetchStatus()
    hasUsers = res.data?.hasUsers ?? true
  } catch {
    hasUsers = true
  }
  setupChecked = true
  return hasUsers
}

export function markSetupComplete() {
  hasUsers = true
  setupChecked = true
}

export function resetSetupCache() {
  setupChecked = false
  hasUsers = true
}
