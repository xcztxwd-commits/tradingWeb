export type TradingCategory = 'crypto' | 'forex' | 'contract'

export const lastTradingSymbolsStorageKey = 'fx-platform-last-trading-symbols'

const defaultSymbols: Record<TradingCategory, string> = {
  crypto: 'BTCUSDT',
  forex: 'EURUSD',
  contract: 'ETHUSDT'
}

export function readLastTradingSymbols(storage = getStorage()) {
  try {
    const parsed = JSON.parse(storage?.getItem(lastTradingSymbolsStorageKey) ?? '{}')
    if (!isRecord(parsed)) return { ...defaultSymbols }
    return {
      crypto: normalizeSymbol(parsed.crypto, defaultSymbols.crypto),
      forex: normalizeSymbol(parsed.forex, defaultSymbols.forex),
      contract: normalizeSymbol(parsed.contract, defaultSymbols.contract)
    }
  } catch {
    return { ...defaultSymbols }
  }
}

export function getLastTradingSymbol(category: TradingCategory, storage = getStorage()) {
  return readLastTradingSymbols(storage)[category]
}

export function writeLastTradingSymbol(category: TradingCategory, symbol: string, storage = getStorage()) {
  const normalized = normalizeSymbol(symbol, defaultSymbols[category])
  try {
    storage?.setItem(
      lastTradingSymbolsStorageKey,
      JSON.stringify({
        ...readLastTradingSymbols(storage),
        [category]: normalized
      })
    )
  } catch {
    // Route generation must not fail in storage-restricted browsers.
  }
  return normalized
}

export function resolveTradingPath(category: TradingCategory, symbol = getLastTradingSymbol(category)) {
  return `/trading?category=${category}&symbol=${encodeURIComponent(symbol)}`
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

function normalizeSymbol(value: unknown, fallback: string) {
  return typeof value === 'string' && value.trim() ? value.trim().toUpperCase() : fallback
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
