export function normalizePlatformMarketSymbol(symbol: string) {
  const upper = symbol.trim().toUpperCase()
  if (/[-_/]PERP$/.test(upper)) {
    return `${upper.slice(0, -'-PERP'.length).replace(/[-_/]/g, '')}-PERP`
  }
  return upper.replace(/[-_/]/g, '')
}
