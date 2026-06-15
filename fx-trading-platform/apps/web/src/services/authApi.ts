import { apiGet, apiPost } from './apiClient'

export type AuthResponse = {
  userId: string
  email: string
  role: string
  accessToken: string
}

export type SessionStatus = {
  status: 'guest' | 'valid_token' | 'invalid_token'
  authenticated: boolean
  userId: string | null
  email: string | null
  role: string | null
  kycStatus?: 'UNVERIFIED' | 'PENDING' | 'APPROVED' | 'REJECTED'
  loginPath: string
}

export type SessionAuthStatus = SessionStatus['status']

export function register(email: string, password: string) {
  return apiPost<AuthResponse>('/api/auth/register', {
    email,
    phone: null,
    password
  })
}

export function login(email: string, password: string) {
  return apiPost<AuthResponse>('/api/auth/login', {
    email,
    password
  })
}

export function getSessionStatus(token?: string | null) {
  return apiGet<SessionStatus>('/api/auth/session', token ?? undefined)
}

export type AuthIdentityCheck = {
  exists: boolean
  channel: 'email' | 'phone'
}

export function checkAuthIdentity(identifier: string, channel: 'email' | 'phone') {
  return apiPost<AuthIdentityCheck>('/api/auth/identity-check', {
    identifier,
    channel
  })
}

export function sendAuthVerificationCode(identifier: string, channel: 'email' | 'phone') {
  return apiPost<{ sent: boolean }>('/api/auth/verification-code', {
    identifier,
    channel
  })
}
