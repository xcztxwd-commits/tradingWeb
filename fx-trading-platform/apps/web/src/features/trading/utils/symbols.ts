export function parseSymbolAssets(symbol: string) {
  const normalized = symbol.replace(/[-_/]/g, '').toUpperCase()
  if (normalized.endsWith('USDT')) return { baseAsset: normalized.slice(0, -4), quoteAsset: 'USDT' }
  if (normalized.endsWith('USD')) return { baseAsset: normalized.slice(0, -3), quoteAsset: 'USD' }
  if (normalized.endsWith('JPY')) return { baseAsset: normalized.slice(0, -3), quoteAsset: 'JPY' }
  return { baseAsset: normalized.slice(0, 3), quoteAsset: normalized.slice(3) || 'USDT' }
}
