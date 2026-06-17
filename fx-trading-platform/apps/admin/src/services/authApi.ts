import { apiPost } from './apiClient'
import type { AuthResponse } from '../types'

export function login(email: string, password: string) {
  return apiPost<AuthResponse>('/api/auth/login', { email, password })
}

export function refreshAdminAuth(refreshToken: string) {
  return apiPost<AuthResponse>('/api/auth/refresh', { refreshToken })
}

export function logoutAdminAuth(accessToken?: string | null, refreshToken?: string | null) {
  return apiPost<null>('/api/auth/logout', { refreshToken: refreshToken ?? null }, accessToken ?? undefined)
}
