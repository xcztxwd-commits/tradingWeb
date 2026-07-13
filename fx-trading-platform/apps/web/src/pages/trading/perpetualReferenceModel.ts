import type { PerpetualReferenceResponse } from '@fx-platform/shared-types'
import type { MarketSource } from '../../features/trading/components/MarketSourceBadge.tsx'

export type PerpetualReferenceView = {
  markPrice: string
  indexPrice: string
  fundingRate: string
  fundingCountdown: string
  marketSource: MarketSource
  providerCode?: string | null
  stale: boolean
}

export function toPerpetualReferenceView(reference: PerpetualReferenceResponse): PerpetualReferenceView {
  return {
    markPrice: formatReferencePrice(reference.mark),
    indexPrice: formatReferencePrice(reference.index),
    // The current backend reference contract does not expose an active funding cycle.
    // Keep the UI explicitly empty instead of fabricating a tradable rate/countdown.
    fundingRate: '--',
    fundingCountdown: '--',
    marketSource: reference.sourceMode ?? 'LIVE',
    providerCode: reference.providerCode,
    stale: reference.stale ?? true
  }
}

function formatReferencePrice(value: number | undefined) {
  if (value === undefined || !Number.isFinite(value) || value <= 0) return '--'
  return value.toLocaleString(undefined, { maximumFractionDigits: 10 })
}
