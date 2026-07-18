import { useEffect, useState } from 'react'

import { authSessionChangedEvent, readStoredAuthToken } from '@fx-platform/frontend-core'

export type HomeAuthVariant = 'guest' | 'authenticated_unverified' | 'authenticated_verified'

export function useHomeAuthVariant() {
  const [variant, setVariant] = useState<HomeAuthVariant>(() => resolveVariant())

  useEffect(() => {
    const refreshVariant = () => setVariant(resolveVariant())

    refreshVariant()
    window.addEventListener(authSessionChangedEvent, refreshVariant)
    return () => window.removeEventListener(authSessionChangedEvent, refreshVariant)
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
