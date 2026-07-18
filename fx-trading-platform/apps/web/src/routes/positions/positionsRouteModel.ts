export function parseProtectionPrice(value: unknown) {
  const text = String(value ?? '').trim()
  if (!text || !/^(?:\d+|\d+\.\d+|\.\d+)$/.test(text)) return null
  const numeric = Number(text)
  return Number.isFinite(numeric) && numeric > 0 ? numeric : null
}
