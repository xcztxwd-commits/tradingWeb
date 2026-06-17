import { apiGet, apiPost } from './apiClient'
import type { AuthResponse, SessionStatus } from '@fx-platform/shared-types'

export type { AuthResponse, SessionStatus }

export type SessionAuthStatus = SessionStatus['status']

export function register(identifier: string, password: string, channel: 'email' | 'phone' = 'email') {
  return apiPost<AuthResponse>('/api/auth/register', {
    email: channel === 'email' ? identifier : null,
    phone: channel === 'phone' ? identifier : null,
    password
  })
}

export function login(email: string, password: string) {
  return apiPost<AuthResponse>('/api/auth/login', {
    email,
    password
  })
}

export function refreshAuth(refreshToken: string) {
  return apiPost<AuthResponse>('/api/auth/refresh', { refreshToken })
}

export function logoutAuth(accessToken?: string | null, refreshToken?: string | null) {
  return apiPost<null>('/api/auth/logout', { refreshToken: refreshToken ?? null }, accessToken ?? undefined)
}

export function getSessionStatus(token?: string | null) {
  return apiGet<SessionStatus>('/api/auth/session', token ?? undefined)
}
