import type { MessageCenterSnapshot, MessageFilter } from '../../engagement/messageCenterController.ts'

export const MESSAGE_PAGE_SIZE = 10

export type MessageRouteModel = Readonly<{
  authenticated: boolean
  snapshot: MessageCenterSnapshot
  filter: MessageFilter
  pageIndex: number
  setFilter(filter: MessageFilter): void
  goToPage(page: number): void
  refresh(): Promise<void>
  markRead(publicationId: string): Promise<boolean>
  markUnread(publicationId: string): Promise<boolean>
  markAllRead(): Promise<boolean>
  hide(publicationId: string): Promise<boolean>
}>

export function parseMessageRouteSearch(search: URLSearchParams): {
  filter: MessageFilter
  page: number
} {
  const rawFilter = search.get('filter')
  const filter = rawFilter === null || rawFilter === 'ALL'
    ? 'ALL'
    : rawFilter === 'UNREAD' ? 'UNREAD' : null
  if (!filter) return { filter: 'ALL', page: 0 }

  const rawPage = search.get('page')
  if (rawPage === null) return { filter, page: 0 }
  if (!/^[1-9]\d*$/u.test(rawPage)) return { filter, page: 0 }
  const pageNumber = Number(rawPage)
  return Number.isSafeInteger(pageNumber)
    ? { filter, page: pageNumber - 1 }
    : { filter, page: 0 }
}

export function createMessagePageRequest(filter: MessageFilter, page: number) {
  return Object.freeze({ filter, page, size: MESSAGE_PAGE_SIZE })
}

export function messagePageNumber(pageIndex: number) {
  return pageIndex + 1
}

export function actionPending(
  pendingActions: readonly string[],
  action: 'read' | 'unread' | 'hide' | 'read-all',
  publicationId?: string
) {
  const key = action === 'read-all' ? action : `${action}:${publicationId ?? ''}`
  return pendingActions.includes(key)
}
