export type SortDirection = 'asc' | 'desc'

export function filterByStatus<T extends { status?: string }>(rows: T[], status: string) {
  if (!status || status === 'ALL') return rows
  return rows.filter((row) => row.status === status)
}

export function sortRows<T extends object>(rows: T[], key: keyof T | '', direction: SortDirection) {
  if (!key) return [...rows]
  const multiplier = direction === 'asc' ? 1 : -1
  return [...rows].sort((left, right) => {
    const field = String(key)
    return compareValues((left as Record<string, unknown>)[field], (right as Record<string, unknown>)[field]) * multiplier
  })
}

export function paginateRows<T>(rows: T[], page: number, pageSize: number) {
  const safePageSize = Math.max(1, pageSize)
  const totalPages = Math.max(1, Math.ceil(rows.length / safePageSize))
  const safePage = Math.min(Math.max(1, page), totalPages)
  const start = (safePage - 1) * safePageSize
  return {
    items: rows.slice(start, start + safePageSize),
    page: safePage,
    pageSize: safePageSize,
    total: rows.length,
    totalPages
  }
}

export function toNumber(value: unknown) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0
  if (typeof value === 'string' && value.trim() !== '') {
    const numeric = Number(value)
    if (Number.isFinite(numeric)) return numeric
  }
  return null
}

function compareValues(left: unknown, right: unknown) {
  const leftNumber = toNumber(left)
  const rightNumber = toNumber(right)
  if (leftNumber !== null && rightNumber !== null) return leftNumber - rightNumber

  const leftTime = toTimestamp(left)
  const rightTime = toTimestamp(right)
  if (leftTime !== null && rightTime !== null) return leftTime - rightTime

  return String(left ?? '').localeCompare(String(right ?? ''))
}

function toTimestamp(value: unknown) {
  if (typeof value !== 'string' || !value.includes('T')) return null
  const timestamp = Date.parse(value)
  return Number.isNaN(timestamp) ? null : timestamp
}
