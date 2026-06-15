export function formatValue(value: unknown) {
  if (value === null || value === undefined || value === '') return ''
  return String(value)
}

export function formatTime(value: string) {
  const timestamp = Date.parse(value)
  if (Number.isNaN(timestamp)) return '-'
  return new Date(timestamp).toLocaleTimeString()
}
