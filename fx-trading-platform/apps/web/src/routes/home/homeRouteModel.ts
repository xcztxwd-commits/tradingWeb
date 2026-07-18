import type { HomeAuthVariant } from './homeRoute.types'

export function resolveHomeAuthVariant(
  token: string | null,
  kycStatus: string | null
): HomeAuthVariant {
  if (!token) return 'guest'
  return kycStatus === 'APPROVED'
    ? 'authenticated_verified'
    : 'authenticated_unverified'
}
