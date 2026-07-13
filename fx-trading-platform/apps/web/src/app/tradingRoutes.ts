export const tradingProducts = ['spot', 'perpetual'] as const

export type TradingProduct = (typeof tradingProducts)[number]

export const tradingProductSymbols = {
  spot: ['BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT'],
  perpetual: ['BTCUSDT-PERP', 'ETHUSDT-PERP', 'BNBUSDT-PERP', 'SOLUSDT-PERP', 'XRPUSDT-PERP']
} as const satisfies Record<TradingProduct, readonly string[]>

export const defaultTradingSymbols = {
  spot: 'BTCUSDT',
  perpetual: 'BTCUSDT-PERP'
} as const satisfies Record<TradingProduct, string>

export function isTradingProduct(value: unknown): value is TradingProduct {
  return value === 'spot' || value === 'perpetual'
}

export function normalizeTradingProductSymbol(product: TradingProduct, value: unknown) {
  if (typeof value !== 'string') return null
  const normalized = value.trim().toUpperCase()
  return tradingProductSymbols[product].some((symbol) => symbol === normalized) ? normalized : null
}

export function buildTradingPath(product: TradingProduct, symbol: string) {
  return `/trade/${product}/${encodeURIComponent(symbol)}`
}

export function resolveSafeTradingPath(product: unknown, symbol?: unknown) {
  const safeProduct = isTradingProduct(product) ? product : 'spot'
  const safeSymbol = normalizeTradingProductSymbol(safeProduct, symbol) ?? defaultTradingSymbols[safeProduct]
  return buildTradingPath(safeProduct, safeSymbol)
}
