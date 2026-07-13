import { useEffect, useState } from 'react'

import { getPerpetualReference } from '../../services/marketApi.ts'
import { toPerpetualReferenceView, type PerpetualReferenceView } from './perpetualReferenceModel.ts'

export function usePerpetualReference(symbol: string, enabled: boolean) {
  const [reference, setReference] = useState<PerpetualReferenceView | null>(null)

  useEffect(() => {
    if (!enabled) {
      setReference(null)
      return
    }
    let active = true
    void getPerpetualReference(symbol)
      .then((response) => {
        if (active) setReference(toPerpetualReferenceView(response))
      })
      .catch(() => {
        if (active) setReference(null)
      })
    return () => {
      active = false
    }
  }, [enabled, symbol])

  return reference
}
