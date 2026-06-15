import { useEffect, useState } from 'react'

import { readStoredAuthToken } from '../../../features/trading-session/tradingSessionStorage'

export type HomeAuthVariant = 'guest' | 'authenticated_unverified' | 'authenticated_verified'

export function useHomeAuthVariant() {
  const [variant, setVariant] = useState<HomeAuthVariant>(() => resolveVariant())

  useEffect(() => {
    setVariant(resolveVariant())
  }, [])

  return variant
}

function resolveVariant(): HomeAuthVariant {
  const token = readStoredAuthToken()
  if (!token) return 'guest'
  try {
    return globalThis.localStorage?.getItem('fx-platform-kyc-status') === 'APPROVED'
      ? 'authenticated_verified'
      : 'authenticated_unverified'
  } catch {
    return 'authenticated_unverified'
  }
}
