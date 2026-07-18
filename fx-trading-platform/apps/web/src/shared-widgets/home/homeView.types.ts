import type { HomeCounters } from '@fx-platform/frontend-core'

export type HomeViewAuthVariant = 'guest' | 'authenticated_unverified' | 'authenticated_verified'
export type HomeViewStatus = 'loading' | 'ready' | 'error'

export type HomeViewMarketPreview = {
  symbol: string
  name: string
  price: string
  change: string
  tone: 'positive' | 'negative'
}

export type HomeViewModel = {
  authVariant: HomeViewAuthVariant
  counters: HomeCounters
  status: HomeViewStatus
  error: string | null
  markets: readonly HomeViewMarketPreview[]
  news: readonly string[]
}
