import {
  clearStoredAuthToken,
  readStoredRefreshToken,
  writeStoredAuthTokens
} from '../auth/sessionStorage.ts'
import { friendlyApiErrorMessage } from '@fx-platform/shared-types'
import type { ApiResponse } from '@fx-platform/shared-types'

type RequestOptions = {
  retryOnAuthFailure?: boolean
}

type AuthRefreshResponse = {
  accessToken: string
  refreshToken: string
}

export type ApiClientErrorInit = {
  status: number
  code: string
  message: string
  requestId?: string
}

export class ApiClientError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId?: string

  constructor(init: ApiClientErrorInit) {
    super(init.message)
    this.name = 'ApiClientError'
    this.status = init.status
    this.code = init.code
    this.requestId = init.requestId
  }
}

const API_BASE =
  (import.meta as ImportMeta & { env?: { VITE_API_BASE_URL?: string } }).env?.VITE_API_BASE_URL ?? ''

export function parseApiErrorPayload(payload: unknown, status: number, requestId?: string): ApiClientErrorInit {
  const body = isRecord(payload) ? payload : {}
  const code = typeof body.code === 'string' && body.code ? body.code : 'REQUEST_FAILED'
  const backendMessage = typeof body.message === 'string' && body.message ? body.message : null
  const fallbackMessage = `Request failed: ${status}`
  return {
    status,
    code,
    message: backendMessage ?? friendlyApiErrorMessage(code, fallbackMessage),
    requestId: requestId ?? (typeof body.requestId === 'string' ? body.requestId : undefined)
  }
}

export async function apiGet<T>(path: string, token?: string, signal?: AbortSignal): Promise<T> {
  return request<T>(path, { method: 'GET', signal }, token)
}

export async function apiPost<T>(path: string, body: unknown, token?: string): Promise<T> {
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

export async function apiPut<T>(path: string, body: unknown, token?: string): Promise<T> {
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

export async function apiPatch<T>(path: string, body: unknown, token?: string): Promise<T> {
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

export async function apiDelete<T>(path: string, token?: string): Promise<T> {
  return request<T>(path, { method: 'DELETE' }, token)
}

async function request<T>(path: string, init: RequestInit, token?: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers
  })
  const payload = (await response.json().catch(() => null)) as ApiResponse<T> | null
  const requestId = response.headers.get('X-Request-Id') ?? payload?.requestId
  if (response.status === 401 && options.retryOnAuthFailure !== false) {
    const refreshedToken = await refreshStoredAuthSession()
    if (refreshedToken) {
      return request<T>(path, init, refreshedToken, { retryOnAuthFailure: false })
    }
  }
  if (!response.ok || !payload?.success) {
    throw new ApiClientError(parseApiErrorPayload(payload, response.status, requestId))
  }
  return payload.data
}

async function refreshStoredAuthSession() {
  const refreshToken = readStoredRefreshToken()
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
    writeStoredAuthTokens(auth.accessToken, auth.refreshToken)
    return auth.accessToken
  } catch {
    clearStoredAuthToken()
    return null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
