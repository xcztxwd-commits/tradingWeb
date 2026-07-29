import { getBrowserStorage, type KeyValueStorage } from '../storage/browserStorage.ts'

export const authSessionChangedEvent = 'fx-platform-auth-session-changed'

const authTokenStorageKey = 'fx-platform-auth-token'
const authRefreshTokenStorageKey = 'fx-platform-auth-refresh-token'
const legacyAuthTokenStorageKey = 'fx-platform-demo-token'

export function readStoredAuthToken(storage = getBrowserStorage()) {
  try {
    const token = storage?.getItem(authTokenStorageKey)
    if (token) {
      clearLegacyStoredAuthToken(storage)
      return token
    }

    const legacyToken = storage?.getItem(legacyAuthTokenStorageKey) ?? null
    if (legacyToken) migrateStoredAuthToken(legacyToken, storage)
    return legacyToken
  } catch {
    return null
  }
}

export function writeStoredAuthToken(token: string, storage = getBrowserStorage()) {
  try {
    storage?.setItem(authTokenStorageKey, token)
    storage?.removeItem(authRefreshTokenStorageKey)
    storage?.removeItem(legacyAuthTokenStorageKey)
  } catch {
    // Login must keep working in browser contexts that block localStorage.
  }
  notifyAuthSessionChanged()
}

export function readStoredRefreshToken(storage = getBrowserStorage()) {
  try {
    return storage?.getItem(authRefreshTokenStorageKey) ?? null
  } catch {
    return null
  }
}

export function writeStoredAuthTokens(
  accessToken: string,
  refreshToken: string,
  storage = getBrowserStorage()
) {
  try {
    storage?.setItem(authTokenStorageKey, accessToken)
    storage?.setItem(authRefreshTokenStorageKey, refreshToken)
    storage?.removeItem(legacyAuthTokenStorageKey)
  } catch {
    // Login must keep working in browser contexts that block localStorage.
  }
  notifyAuthSessionChanged()
}

export function clearStoredAuthToken(storage = getBrowserStorage()) {
  try {
    storage?.removeItem(authTokenStorageKey)
    storage?.removeItem(authRefreshTokenStorageKey)
  } catch {
    // Clearing a best-effort cached token should not block a fresh session.
  }
  try {
    storage?.removeItem(legacyAuthTokenStorageKey)
  } catch {
    // Clearing a best-effort cached token should not block a fresh session.
  }
  notifyAuthSessionChanged()
}

export function readStoredDemoToken(storage = getBrowserStorage()) {
  return readStoredAuthToken(storage)
}

export function writeStoredDemoToken(token: string, storage = getBrowserStorage()) {
  writeStoredAuthToken(token, storage)
}

export function clearStoredDemoToken(storage = getBrowserStorage()) {
  clearStoredAuthToken(storage)
}

function migrateStoredAuthToken(token: string, storage: KeyValueStorage | undefined) {
  try {
    storage?.setItem(authTokenStorageKey, token)
    clearLegacyStoredAuthToken(storage)
  } catch {
    // Keep the legacy token when the migration write cannot be completed.
  }
}

function clearLegacyStoredAuthToken(storage: KeyValueStorage | undefined) {
  try {
    storage?.removeItem(legacyAuthTokenStorageKey)
  } catch {
    // Legacy cleanup is best effort after the formal token is available.
  }
}

function notifyAuthSessionChanged() {
  try {
    if (typeof window === 'undefined') return
    window.dispatchEvent(new Event(authSessionChangedEvent))
  } catch {
    // Storage changes are still valid when the browser blocks custom events.
  }
}
