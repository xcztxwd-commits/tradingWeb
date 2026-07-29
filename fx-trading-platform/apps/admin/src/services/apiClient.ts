import type { ApiResponse } from '../types'
import { friendlyApiErrorMessage } from '@fx-platform/shared-types'
import {
  clearAdminToken,
  getAdminRefreshToken,
  getAdminToken,
  getValidAdminToken,
  setAdminAuthTokens
} from './adminToken.ts'

const API_BASE =
  (import.meta as ImportMeta & { env?: { VITE_API_BASE_URL?: string } }).env?.VITE_API_BASE_URL ?? ''
const REQUEST_ID_HEADER = 'X-Request-Id'

export type ApiClientErrorInit = {
  status: number
  code: string
  message: string
}

export class ApiClientError extends Error {
  readonly status: number
  readonly code: string

  constructor(init: ApiClientErrorInit) {
    super(init.message)
    this.name = 'ApiClientError'
    this.status = init.status
    this.code = init.code
  }
}

type RequestOptions = {
  retryOnAuthFailure?: boolean
}

type AuthRefreshResponse = {
  accessToken: string
  refreshToken: string
  authorities?: string[]
}

type AdminSessionSnapshot = Readonly<{
  accessToken: string | null
  refreshToken: string
}>

let refreshInFlight: Promise<string | null> | null = null
let refreshSessionInFlight: AdminSessionSnapshot | null = null

export function apiGet<T>(path: string, token?: string) {
  return request<T>(path, { method: 'GET' }, token)
}

export function apiPost<T>(
  path: string,
  body: unknown,
  token?: string,
  options: RequestOptions = {}
) {
  return request<T>(
    path,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    token,
    options
  )
}

export function apiPostMultipart<T>(path: string, body: FormData, token: string) {
  return request<T>(path, { method: 'POST', body }, token)
}

export function apiPut<T>(path: string, body: unknown, token: string) {
  return request<T>(
    path,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    token
  )
}

export function apiPatch<T>(path: string, body: unknown, token: string) {
  return request<T>(
    path,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    token
  )
}

export function apiDelete<T>(path: string, body: unknown, token: string) {
  return request<T>(
    path,
    {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    token
  )
}

export function apiRaw(
  path: string,
  init: RequestInit & { signal?: AbortSignal } = {}
): Promise<Response> {
  return requestRaw(path, init, getValidAdminToken())
}

async function request<T>(
  path: string,
  init: RequestInit,
  token?: string,
  options: RequestOptions = {},
  session: AdminSessionSnapshot | null = requestSessionForToken(token)
): Promise<T> {
  const headers = withBearer(init.headers, token)
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  const payload = await readApiPayload<T>(response)
  if (response.status === 401 && options.retryOnAuthFailure !== false) {
    const refreshedToken = await refreshAccessTokenOnce(session)
    if (refreshedToken) {
      return request<T>(path, init, refreshedToken, { retryOnAuthFailure: false }, null)
    }
  }
  if (!response.ok || !payload?.success) {
    throw new ApiClientError(parseApiErrorPayload(payload, response.status))
  }
  return payload.data
}

async function requestRaw(
  path: string,
  init: RequestInit & { signal?: AbortSignal },
  token?: string | null,
  options: RequestOptions = {},
  session: AdminSessionSnapshot | null = requestSessionForToken(token)
): Promise<Response> {
  const headers = withBearer(init.headers, token)
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  if (
    response.status === 401
    && options.retryOnAuthFailure !== false
    && session !== null
    && isCurrentAdminSession(session)
  ) {
    await cancelUnconsumedBody(response)
    const refreshedToken = await refreshAccessTokenOnce(session)
    if (refreshedToken) {
      return requestRaw(path, init, refreshedToken, { retryOnAuthFailure: false }, null)
    }
  }
  return response
}

async function cancelUnconsumedBody(response: Response) {
  try {
    await response.body?.cancel()
  } catch {
    // A failed best-effort cancel must not hide the authoritative 401.
  }
}

function refreshAccessTokenOnce(session: AdminSessionSnapshot | null) {
  if (!session || !isCurrentAdminSession(session)) return Promise.resolve(null)
  if (refreshInFlight && sameAdminSession(refreshSessionInFlight, session)) {
    return refreshInFlight
  }

  const refresh = refreshAdminAuthSession(session).finally(() => {
    if (refreshInFlight !== refresh) return
    refreshInFlight = null
    refreshSessionInFlight = null
  })
  refreshSessionInFlight = session
  refreshInFlight = refresh
  return refresh
}

async function refreshAdminAuthSession(session: AdminSessionSnapshot) {
  try {
    const auth = await request<AuthRefreshResponse>(
      '/api/auth/refresh',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: session.refreshToken })
      },
      undefined,
      { retryOnAuthFailure: false }
    )
    if (!isCurrentAdminSession(session)) return null
    setAdminAuthTokens(auth.accessToken, auth.refreshToken, auth.authorities ?? [])
    return auth.accessToken
  } catch {
    if (isCurrentAdminSession(session)) {
      clearAdminToken()
    }
    return null
  }
}

function requestSessionForToken(token?: string | null): AdminSessionSnapshot | null {
  if (!token) return null
  try {
    const accessToken = getAdminToken()
    const refreshToken = getAdminRefreshToken()
    if (accessToken !== token || !refreshToken) return null
    return {
      accessToken,
      refreshToken
    }
  } catch {
    return null
  }
}

function isCurrentAdminSession(session: AdminSessionSnapshot) {
  return getAdminToken() === session.accessToken
    && getAdminRefreshToken() === session.refreshToken
}

function sameAdminSession(
  left: AdminSessionSnapshot | null,
  right: AdminSessionSnapshot
) {
  return left !== null
    && left.accessToken === right.accessToken
    && left.refreshToken === right.refreshToken
}

function withBearer(headersInit: HeadersInit | undefined, token?: string | null) {
  const headers = new Headers(headersInit)
  if (!headers.has(REQUEST_ID_HEADER)) {
    headers.set(REQUEST_ID_HEADER, crypto.randomUUID())
  }
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  return headers
}

async function readApiPayload<T>(response: Response): Promise<ApiResponse<T> | null> {
  const text = await response.text()
  if (!text.trim()) return null
  try {
    return JSON.parse(text) as ApiResponse<T>
  } catch {
    return null
  }
}

function parseApiErrorPayload(payload: ApiResponse<unknown> | null, status: number): ApiClientErrorInit {
  const fallbackMessage =
    status === 401 || status === 403 ? '登录已过期或无管理员权限，请重新登录' : `请求失败：${status}`
  const code = payload?.code || fallbackCode(status)
  return {
    status,
    code,
    message: friendlyApiErrorMessage(code, payload?.message || fallbackMessage)
  }
}

function fallbackCode(status: number) {
  if (status === 401) return 'AUTH_TOKEN_EXPIRED'
  if (status === 403) return 'FORBIDDEN'
  return 'REQUEST_FAILED'
}
