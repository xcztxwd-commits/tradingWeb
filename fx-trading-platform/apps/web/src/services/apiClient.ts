type ApiResponse<T> = {
  success: boolean
  code: string
  message: string
  data: T
  requestId?: string
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
  return {
    status,
    code: typeof body.code === 'string' && body.code ? body.code : 'REQUEST_FAILED',
    message:
      typeof body.message === 'string' && body.message ? body.message : `Request failed: ${status}`,
    requestId: requestId ?? (typeof body.requestId === 'string' ? body.requestId : undefined)
  }
}

export async function apiGet<T>(path: string, token?: string): Promise<T> {
  return request<T>(path, { method: 'GET' }, token)
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

async function request<T>(path: string, init: RequestInit, token?: string): Promise<T> {
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
  if (!response.ok || !payload?.success) {
    throw new ApiClientError(parseApiErrorPayload(payload, response.status, requestId))
  }
  return payload.data
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
