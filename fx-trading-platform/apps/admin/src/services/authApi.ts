import { apiPost } from './apiClient'
import type { AuthResponse } from '../types'

export function login(email: string, password: string) {
  return apiPost<AuthResponse>('/api/auth/login', { email, password })
}
