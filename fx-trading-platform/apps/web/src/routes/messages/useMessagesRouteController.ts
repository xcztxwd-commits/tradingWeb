import { useEffect, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'

import type { MessageFilter } from '../../engagement/messageCenterController.ts'
import {
  useMessageCenterAuthenticated,
  useMessageCenterRuntime,
  useMessageCenterSnapshot
} from './MessageCenterRuntime.tsx'
import {
  createMessagePageRequest,
  messagePageNumber,
  parseMessageRouteSearch,
  type MessageRouteModel
} from './messageRouteModel.ts'

export function useMessagesRouteController(): MessageRouteModel {
  const runtime = useMessageCenterRuntime()
  const authenticated = useMessageCenterAuthenticated()
  const snapshot = useMessageCenterSnapshot()
  const [search, setSearch] = useSearchParams()
  const route = useMemo(() => parseMessageRouteSearch(search), [search])

  useEffect(() => {
    if (!authenticated) return
    void runtime.loadPage(createMessagePageRequest(route.filter, route.page))
  }, [authenticated, route.filter, route.page, runtime])

  const setFilter = (filter: MessageFilter) => {
    setSearch(filter === 'ALL' ? {} : { filter }, { replace: true })
  }
  const goToPage = (page: number) => {
    if (!Number.isSafeInteger(page) || page < 0) return
    const totalPages = snapshot.page?.totalPages ?? 0
    if (totalPages > 0 && page >= totalPages) return
    const next = new URLSearchParams()
    if (route.filter === 'UNREAD') next.set('filter', route.filter)
    if (page > 0) next.set('page', String(messagePageNumber(page)))
    setSearch(next, { replace: true })
  }

  return {
    authenticated,
    snapshot,
    filter: route.filter,
    pageIndex: route.page,
    setFilter,
    goToPage,
    refresh: runtime.refreshAll,
    markRead: runtime.markRead,
    markUnread: runtime.markUnread,
    markAllRead: runtime.markAllRead,
    hide: runtime.hide
  }
}
