import { useEffect, useRef, useState } from 'react'

import { getPerpetualReference } from '../../services/marketApi.ts'
import { toPerpetualReferenceView, type PerpetualReferenceView } from './perpetualReferenceModel.ts'
import type { MarketSourceMetadata } from '../../features/market/tradingModels.ts'

export function usePerpetualReference(symbol: string, enabled: boolean, expectedSource?: MarketSourceMetadata) {
  const [reference, setReference] = useState<PerpetualReferenceView | null>(null)
  const expectedSourceRef = useRef(expectedSource)
  expectedSourceRef.current = expectedSource
  const expectedSourceKey = expectedSource ? `${expectedSource.providerCode}|${expectedSource.providerSymbol}|${expectedSource.sourceMode}` : ''

  useEffect(() => {
    if (!enabled) {
      setReference(null)
      return
    }
    let active = true
    let refreshRunning = false
    const refresh = () => {
      if (refreshRunning) return
      refreshRunning = true
      void getPerpetualReference(symbol)
        .then((response) => {
          if (active) setReference(toPerpetualReferenceView(response, expectedSourceRef.current))
        })
        .catch(() => {
          if (active) setReference(null)
        })
        .finally(() => {
          refreshRunning = false
        })
    }
    refresh()
    const refreshTimer = globalThis.setInterval(refresh, 1_000)
    return () => {
      active = false
      globalThis.clearInterval(refreshTimer)
    }
  }, [enabled, expectedSourceKey, symbol])

  return reference
}
