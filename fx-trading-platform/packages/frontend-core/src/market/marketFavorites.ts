import { getBrowserStorage, type KeyValueStorage } from '../storage/browserStorage.ts'
import type { TradingMarket } from './tradingModels.ts'

export const marketFavoriteStorageKey = 'fx-trading-market-favorites'

export function normalizeFavoriteSymbol(symbol: string) {
  return symbol.trim().toUpperCase()
}

export function loadFavoriteSymbols(storage: KeyValueStorage | undefined = getBrowserStorage()) {
  if (!storage) return new Set<string>()
  try {
    const parsed = JSON.parse(storage.getItem(marketFavoriteStorageKey) ?? '[]')
    if (!Array.isArray(parsed)) return new Set<string>()
    return new Set(parsed.filter((symbol): symbol is string => typeof symbol === 'string').map(normalizeFavoriteSymbol).filter(Boolean))
  } catch {
    return new Set<string>()
  }
}

export function saveFavoriteSymbols(favorites: Set<string>, storage: KeyValueStorage | undefined = getBrowserStorage()) {
  if (!storage) return
  const symbols = favoriteSymbolList(favorites)
  try {
    storage.setItem(marketFavoriteStorageKey, JSON.stringify(symbols))
  } catch {
    // Favorites still work for the current render when browser storage is unavailable.
  }
}

export function toggleFavoriteSymbol(favorites: Set<string>, symbol: string) {
  const normalizedSymbol = normalizeFavoriteSymbol(symbol)
  const next = new Set(favoriteSymbolList(favorites))
  if (!normalizedSymbol) return next
  if (next.has(normalizedSymbol)) next.delete(normalizedSymbol)
  else next.add(normalizedSymbol)
  return next
}

export function hydrateMarketFavorites<T extends TradingMarket>(markets: T[], favorites: Set<string>): T[] {
  const favoriteSymbols = new Set(favoriteSymbolList(favorites))
  return markets.map((market) => ({
    ...market,
    favorite: market.favorite || favoriteSymbols.has(normalizeFavoriteSymbol(market.symbol))
  }))
}

export function favoriteSymbolList(favorites: Set<string> | string[]) {
  const symbols = favorites instanceof Set ? Array.from(favorites) : favorites
  return Array.from(new Set(symbols.map(normalizeFavoriteSymbol).filter(Boolean)))
}
