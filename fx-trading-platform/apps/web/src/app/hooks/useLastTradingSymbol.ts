import {
  buildTradingPath,
  defaultTradingSymbols,
  normalizeTradingProductSymbol,
  type TradingProduct
} from '../tradingRoutes.ts'

export type TradingCategory = 'crypto' | 'forex' | 'contract'
type TradingDestination = TradingProduct | TradingCategory

export const lastTradingSymbolsStorageKey = 'fx-platform-last-trading-symbols:v2'

export function readLastTradingSymbols(storage = getStorage()) {
  try {
    const parsed = JSON.parse(storage?.getItem(lastTradingSymbolsStorageKey) ?? '{}')
    if (!isRecord(parsed) || parsed.version !== 2) return { ...defaultTradingSymbols }
    return {
      spot: normalizeTradingProductSymbol('spot', parsed.spot) ?? defaultTradingSymbols.spot,
      perpetual: normalizeTradingProductSymbol('perpetual', parsed.perpetual) ?? defaultTradingSymbols.perpetual
    }
  } catch {
    return { ...defaultTradingSymbols }
  }
}

export function getLastTradingSymbol(destination: TradingDestination, storage = getStorage()) {
  const product = toTradingProduct(destination)
  return readLastTradingSymbols(storage)[product]
}

export function writeLastTradingSymbol(destination: TradingDestination, symbol: string, storage = getStorage()) {
  const product = toTradingProduct(destination)
  const normalized = normalizeTradingProductSymbol(product, symbol) ?? defaultTradingSymbols[product]
  try {
    storage?.setItem(
      lastTradingSymbolsStorageKey,
      JSON.stringify({
        version: 2,
        ...readLastTradingSymbols(storage),
        [product]: normalized
      })
    )
  } catch {
    // Route generation must not fail in storage-restricted browsers.
  }
  return normalized
}

export function resolveTradingPath(
  destination: TradingDestination,
  symbol?: string,
  storage = getStorage()
) {
  const product = toTradingProduct(destination)
  const selectedSymbol = symbol === undefined ? getLastTradingSymbol(product, storage) : symbol
  const normalized = normalizeTradingProductSymbol(product, selectedSymbol) ?? defaultTradingSymbols[product]
  return buildTradingPath(product, normalized)
}

export function inferTradingCategory(symbol: string): TradingCategory {
  const normalized = symbol.toUpperCase()
  if (['EURUSD', 'GBPUSD', 'USDJPY', 'AUDUSD', 'XAUUSD'].includes(normalized)) return 'forex'
  if (normalized.endsWith('USDT')) return 'crypto'
  return 'contract'
}

export function normalizeTradingCategory(value: string | null): TradingCategory {
  return value === 'forex' || value === 'contract' || value === 'crypto' ? value : 'crypto'
}

function toTradingProduct(destination: TradingDestination): TradingProduct {
  return destination === 'contract' || destination === 'perpetual' ? 'perpetual' : 'spot'
}

function getStorage(): Pick<Storage, 'getItem' | 'setItem'> | undefined {
  try {
    return globalThis.localStorage
  } catch {
    return undefined
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
