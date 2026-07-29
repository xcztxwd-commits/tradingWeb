import {
  clearStoredAuthToken,
  readStoredRefreshToken,
  writeStoredAuthTokens
} from '../auth/sessionStorage.ts'
import { friendlyApiErrorMessage } from '@fx-platform/shared-types'
import type { ApiResponse } from '@fx-platform/shared-types'

export type ApiRequestOptions = {
  retryOnAuthFailure?: boolean
  signal?: AbortSignal
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

export async function apiGet<T>(path: string, token?: string, options?: ApiRequestOptions): Promise<T> {
  return request<T>(path, { method: 'GET' }, token, options)
}

export async function apiPost<T>(path: string, body: unknown, token?: string, options?: ApiRequestOptions): Promise<T> {
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

export async function apiPut<T>(path: string, body: unknown, token?: string, options?: ApiRequestOptions): Promise<T> {
  return request<T>(
    path,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    token,
    options
  )
}

export async function apiPatch<T>(path: string, body: unknown, token?: string, options?: ApiRequestOptions): Promise<T> {
  return request<T>(
    path,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    token,
    options
  )
}

export async function apiDelete<T>(path: string, token?: string, options?: ApiRequestOptions): Promise<T> {
  return request<T>(path, { method: 'DELETE' }, token, options)
}

async function request<T>(path: string, init: RequestInit, token?: string, options: ApiRequestOptions = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers,
    signal: options.signal
  })
  const payload = (await response.json().catch(() => null)) as ApiResponse<T> | null
  const requestId = response.headers.get('X-Request-Id') ?? payload?.requestId
  if (response.status === 401 && options.retryOnAuthFailure !== false) {
    const refreshedToken = await refreshStoredAuthSession(options.signal)
    if (refreshedToken) {
      return request<T>(path, init, refreshedToken, { ...options, retryOnAuthFailure: false })
    }
  }
  if (!response.ok || !payload?.success) {
    throw new ApiClientError(parseApiErrorPayload(payload, response.status, requestId))
  }
  return payload.data
}

async function refreshStoredAuthSession(signal?: AbortSignal) {
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
      { retryOnAuthFailure: false, signal }
    )
    writeStoredAuthTokens(auth.accessToken, auth.refreshToken)
    return auth.accessToken
  } catch (error) {
    if (signal?.aborted) throw signal.reason ?? error
    clearStoredAuthToken()
    return null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
