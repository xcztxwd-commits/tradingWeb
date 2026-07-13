import type { PerpetualReferenceResponse } from '@fx-platform/shared-types'
import type { MarketSource } from '../../features/trading/components/MarketSourceBadge.tsx'
import { isFreshSource } from '../../features/market/authoritativeMarketSnapshot.ts'
import type { MarketSourceMetadata } from '../../features/market/tradingModels.ts'

export type PerpetualReferenceView = {
  markPrice: string
  indexPrice: string
  fundingRate: string
  fundingCountdown: string
  marketSource: MarketSource
  providerCode?: string | null
  stale: boolean
}

export function toPerpetualReferenceView(
  reference: PerpetualReferenceResponse,
  expectedSource?: MarketSourceMetadata,
  now = Date.now()
): PerpetualReferenceView {
  const source = toReferenceSource(reference)
  const stale = reference.stale ?? true
  const sourceMismatch = Boolean(expectedSource && (!source || !sameProviderSource(source, expectedSource)))
  const unavailable = stale
    || sourceMismatch
    || Boolean(source && !isFreshSource(source, now))
    || Boolean(expectedSource && !isFreshSource(expectedSource, now))
  return {
    markPrice: unavailable ? '--' : formatReferencePrice(reference.mark),
    indexPrice: unavailable ? '--' : formatReferencePrice(reference.index),
    // The current backend reference contract does not expose an active funding cycle.
    // Keep the UI explicitly empty instead of fabricating a tradable rate/countdown.
    fundingRate: '--',
    fundingCountdown: '--',
    marketSource: reference.sourceMode ?? 'LIVE',
    providerCode: reference.providerCode,
    stale: unavailable
  }
}

function toReferenceSource(reference: PerpetualReferenceResponse): MarketSourceMetadata | undefined {
  if (!reference.providerCode || !reference.providerSymbol || !reference.sourceMode || !reference.asOf || !reference.expiresAt) {
    return undefined
  }
  return {
    providerCode: reference.providerCode,
    providerSymbol: reference.providerSymbol,
    sourceMode: reference.sourceMode,
    asOf: reference.asOf,
    expiresAt: reference.expiresAt,
    stale: reference.stale ?? true
  }
}

function sameProviderSource(left: MarketSourceMetadata, right: MarketSourceMetadata) {
  return left.providerCode === right.providerCode
    && left.providerSymbol === right.providerSymbol
    && left.sourceMode === right.sourceMode
}

function formatReferencePrice(value: number | undefined) {
  if (value === undefined || !Number.isFinite(value) || value <= 0) return '--'
  return value.toLocaleString(undefined, { maximumFractionDigits: 10 })
}
