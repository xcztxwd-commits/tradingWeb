import type { AuthChannel, AuthRouteMode } from './authRoute.types'

export function resolveSafeAuthRedirect(value: string | null) {
  if (!value || !value.startsWith('/') || value.startsWith('//')) return '/account/overview'
  return value
}

export function normalizeRegistrationIdentifier(
  channel: AuthChannel,
  countryCode: string,
  identifier: string
) {
  const normalized = identifier.trim()
  return channel === 'phone' ? `${countryCode}${normalized}` : normalized
}

export function getAuthSuccessPath(mode: AuthRouteMode, loginRedirect: string) {
  return mode === 'login' ? loginRedirect : '/account/overview'
}
