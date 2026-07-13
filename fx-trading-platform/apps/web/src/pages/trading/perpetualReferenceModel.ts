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
  const funding = formatFundingCycle(reference.fundingRate, reference.nextFundingTime, now, unavailable)
  return {
    markPrice: unavailable ? '--' : formatReferencePrice(reference.mark),
    indexPrice: unavailable ? '--' : formatReferencePrice(reference.index),
    fundingRate: funding.rate,
    fundingCountdown: funding.countdown,
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

function formatFundingCycle(
  rate: number | undefined,
  nextFundingTime: string | undefined,
  now: number,
  unavailable: boolean
) {
  const target = nextFundingTime ? Date.parse(nextFundingTime) : Number.NaN
  if (unavailable || rate === undefined || !Number.isFinite(rate) || !Number.isFinite(target) || target <= now) {
    return { rate: '--', countdown: '--' }
  }
  const remainingSeconds = Math.ceil((target - now) / 1_000)
  const hours = Math.floor(remainingSeconds / 3_600)
  const minutes = Math.floor((remainingSeconds % 3_600) / 60)
  const seconds = remainingSeconds % 60
  return {
    rate: `${(rate * 100).toFixed(4)}%`,
    countdown: [hours, minutes, seconds].map((value) => String(value).padStart(2, '0')).join(':')
  }
}
