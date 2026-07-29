export type DataSortDirection = 'asc' | 'desc'

export type DataSortState = {
  key: string | null
  direction: DataSortDirection
}

export function getNextDataSort(current: DataSortState, nextKey: string): DataSortState {
  if (current.key !== nextKey) return { key: nextKey, direction: 'asc' }
  return { key: nextKey, direction: current.direction === 'asc' ? 'desc' : 'asc' }
}

export function getNextPage(currentPage: number, delta: -1 | 1, totalPages: number) {
  const lastPage = Math.max(1, totalPages)
  return Math.min(lastPage, Math.max(1, currentPage + delta))
}
