import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import type { ShellChromeModel } from '../shell/shellChromeModel'
import { TopbarToolIcon } from '../../components/TopbarToolIcon'
import styles from './NotificationBell.module.css'

type NotificationBellProps = {
  model: Pick<ShellChromeModel, 'unreadCount' | 'recentMessages' | 'messageSummaryLoading'>
  mode: 'menu' | 'link'
}

export function NotificationBell({ model, mode }: NotificationBellProps) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const badge = model.unreadCount > 99 ? '99+' : String(model.unreadCount)

  useEffect(() => {
    if (!open) return undefined
    const handlePointer = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false)
    }
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('pointerdown', handlePointer)
    document.addEventListener('keydown', handleKey)
    return () => {
      document.removeEventListener('pointerdown', handlePointer)
      document.removeEventListener('keydown', handleKey)
    }
  }, [open])

  if (mode === 'link') {
    return (
      <Link className={styles.mobileLink} to="/messages" aria-label={messageLabel(model.unreadCount)}>
        <TopbarToolIcon name="bell" />
        {model.unreadCount > 0 && <span className={styles.badge}>{badge}</span>}
      </Link>
    )
  }

  return (
    <div className={styles.root} ref={rootRef}>
      <button
        type="button"
        className={styles.trigger}
        aria-label={messageLabel(model.unreadCount)}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <TopbarToolIcon name="bell" />
        {model.unreadCount > 0 && <span className={styles.badge}>{badge}</span>}
      </button>

      {open && (
        <section className={styles.panel} role="dialog" aria-label="Recent messages">
          <div className={styles.heading}>
            <strong>Messages</strong>
            <span>{model.unreadCount} unread</span>
          </div>
          <div className={styles.list} aria-live="polite">
            {model.messageSummaryLoading && model.recentMessages.length === 0 ? (
              <p className={styles.state}>Loading messages…</p>
            ) : model.recentMessages.length === 0 ? (
              <p className={styles.state}>No recent messages</p>
            ) : model.recentMessages.map((message) => (
              <Link
                key={message.publicationId}
                className={styles.item}
                data-unread={message.readState === 'UNREAD' || undefined}
                to="/messages"
                onClick={() => setOpen(false)}
              >
                <span className={styles.itemTitle}>{message.content.title}</span>
                <span className={styles.itemMeta}>{message.category}</span>
              </Link>
            ))}
          </div>
          <Link className={styles.allLink} to="/messages" onClick={() => setOpen(false)}>
            View all messages
          </Link>
        </section>
      )}
    </div>
  )
}

function messageLabel(unreadCount: number) {
  return unreadCount > 0 ? `Messages, ${unreadCount} unread` : 'Messages'
}
