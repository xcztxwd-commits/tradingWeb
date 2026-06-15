export const tokenStorageKey = 'fx-platform-admin-token'

export function getAdminToken() {
  return localStorage.getItem(tokenStorageKey)
}

export function getValidAdminToken() {
  const token = getAdminToken()
  if (!token) return null
  if (isJwtExpired(token)) {
    clearAdminToken()
    return null
  }
  return token
}

export function setAdminToken(token: string) {
  localStorage.setItem(tokenStorageKey, token)
}

export function clearAdminToken() {
  localStorage.removeItem(tokenStorageKey)
}

function isJwtExpired(token: string) {
  const payload = readJwtPayload(token)
  if (typeof payload?.exp !== 'number') return true
  return payload.exp * 1000 <= Date.now()
}

function readJwtPayload(token: string): { exp?: number } | null {
  const [, payload] = token.split('.')
  if (!payload) return null
  try {
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
    return JSON.parse(atob(padded)) as { exp?: number }
  } catch {
    return null
  }
}
