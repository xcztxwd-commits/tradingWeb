export function parseSymbolAssets(symbol: string) {
  const platformSymbol = symbol.trim().toUpperCase()
  const instrumentSymbol = platformSymbol.endsWith('-PERP')
    ? platformSymbol.slice(0, -'-PERP'.length)
    : platformSymbol
  const normalized = instrumentSymbol.replace(/[-_/]/g, '')
  if (normalized.endsWith('USDT')) return { baseAsset: normalized.slice(0, -4), quoteAsset: 'USDT' }
  if (normalized.endsWith('USD')) return { baseAsset: normalized.slice(0, -3), quoteAsset: 'USD' }
  if (normalized.endsWith('JPY')) return { baseAsset: normalized.slice(0, -3), quoteAsset: 'JPY' }
  return { baseAsset: normalized.slice(0, 3), quoteAsset: normalized.slice(3) || 'USDT' }
}
