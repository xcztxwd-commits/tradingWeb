import type { ApiResponse } from '../types'
import { friendlyApiErrorMessage } from '@fx-platform/shared-types'
import { clearAdminToken, getAdminRefreshToken, setAdminAuthTokens } from './adminToken.ts'

const API_BASE =
  (import.meta as ImportMeta & { env?: { VITE_API_BASE_URL?: string } }).env?.VITE_API_BASE_URL ?? ''

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

export function apiGet<T>(path: string, token?: string) {
  return request<T>(path, { method: 'GET' }, token)
}

export function apiPost<T>(path: string, body: unknown, token?: string) {
  return request<T>(
    path,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    token
  )
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

async function request<T>(path: string, init: RequestInit, token?: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  const payload = await readApiPayload<T>(response)
  if (response.status === 401 && options.retryOnAuthFailure !== false) {
    const refreshedToken = await refreshAdminAuthSession()
    if (refreshedToken) {
      return request<T>(path, init, refreshedToken, { retryOnAuthFailure: false })
    }
  }
  if (!response.ok || !payload?.success) {
    throw new ApiClientError(parseApiErrorPayload(payload, response.status))
  }
  return payload.data
}

async function refreshAdminAuthSession() {
  const refreshToken = getAdminRefreshToken()
  if (!refreshToken) return null
  try {
    const auth = await request<AuthRefreshResponse>(
      '/api/auth/refresh',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken })
      },
      undefined,
      { retryOnAuthFailure: false }
    )
    setAdminAuthTokens(auth.accessToken, auth.refreshToken, auth.authorities ?? [])
    return auth.accessToken
  } catch {
    clearAdminToken()
    return null
  }
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
