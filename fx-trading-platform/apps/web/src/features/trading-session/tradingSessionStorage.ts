type AuthTokenStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

const authTokenStorageKey = 'fx-platform-auth-token'
const legacyAuthTokenStorageKey = 'fx-platform-demo-token'

export function readStoredAuthToken(storage = getAuthTokenStorage()) {
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

export function writeStoredAuthToken(token: string, storage = getAuthTokenStorage()) {
  try {
    storage?.setItem(authTokenStorageKey, token)
    storage?.removeItem(legacyAuthTokenStorageKey)
  } catch {
    // Login must keep working in browser contexts that block localStorage.
  }
}

export function clearStoredAuthToken(storage = getAuthTokenStorage()) {
  try {
    storage?.removeItem(authTokenStorageKey)
  } catch {
    // Clearing a best-effort cached token should not block a fresh session.
  }
  try {
    storage?.removeItem(legacyAuthTokenStorageKey)
  } catch {
    // Clearing a best-effort cached token should not block a fresh session.
  }
}

export function readStoredDemoToken(storage = getAuthTokenStorage()) {
  return readStoredAuthToken(storage)
}

export function writeStoredDemoToken(token: string, storage = getAuthTokenStorage()) {
  writeStoredAuthToken(token, storage)
}

export function clearStoredDemoToken(storage = getAuthTokenStorage()) {
  clearStoredAuthToken(storage)
}

function getAuthTokenStorage(): AuthTokenStorage | undefined {
  try {
    return globalThis.localStorage
  } catch {
    return undefined
  }
}

function migrateStoredAuthToken(token: string, storage: AuthTokenStorage | undefined) {
  try {
    storage?.setItem(authTokenStorageKey, token)
    clearLegacyStoredAuthToken(storage)
  } catch {
    // Keep the legacy token when the migration write cannot be completed.
  }
}

function clearLegacyStoredAuthToken(storage: AuthTokenStorage | undefined) {
  try {
    storage?.removeItem(legacyAuthTokenStorageKey)
  } catch {
    // Legacy cleanup is best effort after the formal token is available.
  }
}
