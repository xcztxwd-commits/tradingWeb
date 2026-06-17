export const tokenStorageKey = 'fx-platform-admin-token'
export const refreshTokenStorageKey = 'fx-platform-admin-refresh-token'
export const authorityStorageKey = 'fx-platform-admin-authorities'

export function getAdminToken() {
  return localStorage.getItem(tokenStorageKey)
}

export function getValidAdminToken() {
  const token = getAdminToken()
  if (!token) return null
  if (isJwtExpired(token)) {
    if (getAdminRefreshToken()) return token
    clearAdminToken()
    return null
  }
  return token
}

export function getAdminRefreshToken() {
  return localStorage.getItem(refreshTokenStorageKey)
}

export function getAdminAuthorities() {
  const stored = localStorage.getItem(authorityStorageKey)
  if (!stored) return []
  try {
    const values = JSON.parse(stored)
    return Array.isArray(values) ? values.map(String).filter(Boolean) : []
  } catch {
    localStorage.removeItem(authorityStorageKey)
    return []
  }
}

export function setAdminToken(token: string) {
  localStorage.setItem(tokenStorageKey, token)
  localStorage.removeItem(refreshTokenStorageKey)
  localStorage.removeItem(authorityStorageKey)
}

export function setAdminAuthTokens(accessToken: string, refreshToken: string, authorities: string[] = []) {
  localStorage.setItem(tokenStorageKey, accessToken)
  localStorage.setItem(refreshTokenStorageKey, refreshToken)
  localStorage.setItem(authorityStorageKey, JSON.stringify(authorities))
}

export function clearAdminToken() {
  localStorage.removeItem(tokenStorageKey)
  localStorage.removeItem(refreshTokenStorageKey)
  localStorage.removeItem(authorityStorageKey)
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
