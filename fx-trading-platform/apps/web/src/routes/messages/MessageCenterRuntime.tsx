import {
  createContext,
  useContext,
  useSyncExternalStore,
  type ReactNode
} from 'react'

import type {
  MessageCenterSnapshot,
  MessageFilter
} from '../../engagement/messageCenterController.ts'

export type MessageCenterRuntime = Readonly<{
  getSnapshot(): MessageCenterSnapshot
  subscribe(listener: () => void): () => void
  refreshAll(): Promise<void>
  loadPage(request: { filter: MessageFilter; page: number; size: number }): Promise<void>
  markRead(publicationId: string): Promise<boolean>
  markUnread(publicationId: string): Promise<boolean>
  markAllRead(): Promise<boolean>
  hide(publicationId: string): Promise<boolean>
}>

type MessageCenterContextValue = Readonly<{
  runtime: MessageCenterRuntime
  authenticated: boolean
}>

const MessageCenterContext = createContext<MessageCenterContextValue | null>(null)

export function MessageCenterRuntimeProvider({
  runtime,
  authenticated,
  children
}: {
  runtime: MessageCenterRuntime
  authenticated: boolean
  children: ReactNode
}) {
  return (
    <MessageCenterContext.Provider value={{ runtime, authenticated }}>
      {children}
    </MessageCenterContext.Provider>
  )
}

export function useMessageCenterRuntime() {
  const context = useContext(MessageCenterContext)
  if (!context) throw new Error('MessageCenterRuntimeProvider is missing')
  return context.runtime
}

export function useMessageCenterAuthenticated() {
  const context = useContext(MessageCenterContext)
  if (!context) throw new Error('MessageCenterRuntimeProvider is missing')
  return context.authenticated
}

export function useMessageCenterSnapshot() {
  const runtime = useMessageCenterRuntime()
  return useSyncExternalStore(runtime.subscribe, runtime.getSnapshot, runtime.getSnapshot)
}
