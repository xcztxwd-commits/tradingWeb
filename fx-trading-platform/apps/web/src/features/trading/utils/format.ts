export function formatDecimal(value: number, decimals = 8) {
  if (!Number.isFinite(value) || value <= 0) return ''
  return value.toFixed(decimals).replace(/\.?0+$/, '')
}
