import { Link } from 'react-router-dom'
import type { MessageModel } from '@fx-platform/frontend-core'

import {
  prepareEngagementHtml,
  resolveEngagementRoute
} from '../../engagement/engagementNavigation.ts'
import { actionPending, type MessageRouteModel } from './messageRouteModel.ts'
import styles from './MessageCenterContent.module.css'

export function MessageCenterContent({
  model,
  platform
}: {
  model: MessageRouteModel
  platform: 'pc' | 'mobile'
}) {
  if (!model.authenticated) {
    return (
      <section className={styles.root} data-messages-platform={platform} aria-labelledby="messages-title">
        <header className={styles.header}>
          <div>
            <p className={styles.eyebrow}>Inbox</p>
            <h1 id="messages-title">Message center</h1>
            <p className={styles.summary}>Sign in to see your messages and unread updates.</p>
          </div>
          <Link className={styles.primaryButton} to="/login?redirect=%2Fmessages">Sign in</Link>
        </header>
      </section>
    )
  }

  const { snapshot, filter } = model
  const { page, pageLoading, pendingActions, error } = snapshot
  const readAllPending = actionPending(pendingActions, 'read-all')

  return (
    <section className={styles.root} data-messages-platform={platform} aria-labelledby="messages-title">
      <header className={styles.header}>
        <div>
          <p className={styles.eyebrow}>Inbox</p>
          <h1 id="messages-title">Message center</h1>
          <p className={styles.summary}>
            {snapshot.unreadCount === 0
              ? 'You are all caught up.'
              : `${snapshot.unreadCount} unread ${snapshot.unreadCount === 1 ? 'message' : 'messages'}`}
          </p>
        </div>
        <div className={styles.headerActions}>
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={() => void model.refresh()}
            disabled={snapshot.summaryLoading || pageLoading}
          >
            Refresh
          </button>
          <button
            type="button"
            className={styles.primaryButton}
            onClick={() => void model.markAllRead()}
            disabled={snapshot.unreadCount === 0 || readAllPending}
            aria-busy={readAllPending}
          >
            {readAllPending ? 'Marking…' : 'Mark all as read'}
          </button>
        </div>
      </header>

      <div className={styles.tabs} role="tablist" aria-label="Message filter">
        <button
          type="button"
          role="tab"
          aria-selected={filter === 'ALL'}
          className={filter === 'ALL' ? styles.activeTab : styles.tab}
          onClick={() => model.setFilter('ALL')}
        >
          All
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={filter === 'UNREAD'}
          className={filter === 'UNREAD' ? styles.activeTab : styles.tab}
          onClick={() => model.setFilter('UNREAD')}
        >
          Unread <span className={styles.count}>{snapshot.unreadCount}</span>
        </button>
      </div>

      {error ? (
        <div className={styles.error} role="alert">
          <div><strong>Messages could not be updated.</strong><span>{error}</span></div>
          <button type="button" onClick={() => void model.refresh()}>Try again</button>
        </div>
      ) : null}

      <div className={styles.loadingLine} aria-live="polite">
        {pageLoading || snapshot.summaryLoading ? 'Updating messages…' : ''}
      </div>

      {!page && pageLoading ? <MessageState title="Loading messages…" detail="This should only take a moment." /> : null}
      {!page && !pageLoading && !error ? <MessageState title="Loading messages…" detail="Connecting to your inbox." /> : null}
      {page && page.items.length === 0 && !pageLoading ? (
        <MessageState
          title={filter === 'UNREAD' ? 'No unread messages' : 'No messages yet'}
          detail={filter === 'UNREAD' ? 'New unread messages will appear here.' : 'Updates and campaign messages will appear here.'}
        />
      ) : null}

      {page && page.items.length > 0 ? (
        <ol className={styles.list} aria-label={`${filter === 'UNREAD' ? 'Unread' : 'All'} messages`}>
          {page.items.map((message) => (
            <MessageCard key={message.publicationId} message={message} model={model} />
          ))}
        </ol>
      ) : null}

      {page ? (
        <nav className={styles.pagination} aria-label="Message pages">
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={() => model.goToPage(model.pageIndex - 1)}
            disabled={model.pageIndex === 0 || pageLoading}
          >
            Previous
          </button>
          <span>
            Page {page.page + 1} of {Math.max(1, page.totalPages)}
            <small>{page.total} total</small>
          </span>
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={() => model.goToPage(model.pageIndex + 1)}
            disabled={pageLoading || page.totalPages === 0 || model.pageIndex + 1 >= page.totalPages}
          >
            Next
          </button>
        </nav>
      ) : null}
    </section>
  )
}

function MessageCard({ message, model }: { message: MessageModel; model: MessageRouteModel }) {
  const { pendingActions } = model.snapshot
  const receiptAction = message.readState === 'UNREAD' ? 'read' : 'unread'
  const receiptPending = actionPending(pendingActions, receiptAction, message.publicationId)
  const hidePending = actionPending(pendingActions, 'hide', message.publicationId)
  const rowPending = receiptPending || hidePending
  const safeHtml = prepareEngagementHtml(message.content.sanitizedHtml)
  const ctaHref = message.content.cta
    ? resolveEngagementRoute(message.content.cta.routeKey, message.content.cta.params)
    : null

  return (
    <li>
      <article
        className={`${styles.card} ${message.readState === 'UNREAD' ? styles.unreadCard : ''}`}
        aria-labelledby={`message-title-${message.publicationId}`}
        aria-busy={rowPending}
      >
        {message.content.coverAsset ? (
          <img
            className={styles.cover}
            src={message.content.coverAsset.url}
            alt=""
            data-asset-id={message.content.coverAsset.assetId}
          />
        ) : null}
        <div className={styles.cardBody}>
          <div className={styles.meta}>
            <span className={styles.category}>{message.category}</span>
            {message.readState === 'UNREAD' ? <span className={styles.unreadLabel}>Unread</span> : null}
            <time dateTime={message.sentAt}>{formatMessageTime(message.sentAt)}</time>
          </div>
          <h2 id={`message-title-${message.publicationId}`}>{message.content.title}</h2>
          {safeHtml ? (
            <div className={styles.messageHtml} dangerouslySetInnerHTML={{ __html: safeHtml }} />
          ) : (
            <p className={styles.unavailable} role="alert">Message content is unavailable.</p>
          )}
          <div className={styles.cardActions}>
            {ctaHref && message.content.cta ? (
              <Link className={styles.cta} to={ctaHref}>{message.content.cta.label}</Link>
            ) : null}
            <button
              type="button"
              className={styles.secondaryButton}
              onClick={() => void (message.readState === 'UNREAD'
                ? model.markRead(message.publicationId)
                : model.markUnread(message.publicationId))}
              disabled={rowPending}
              aria-busy={receiptPending}
            >
              {receiptPending
                ? 'Saving…'
                : message.readState === 'UNREAD' ? 'Mark as read' : 'Mark as unread'}
            </button>
            <button
              type="button"
              className={styles.hideButton}
              onClick={() => void model.hide(message.publicationId)}
              disabled={rowPending}
              aria-busy={hidePending}
            >
              {hidePending ? 'Hiding…' : 'Hide'}
            </button>
          </div>
        </div>
      </article>
    </li>
  )
}

function MessageState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className={styles.state} role="status">
      <span aria-hidden="true">✦</span>
      <strong>{title}</strong>
      <p>{detail}</p>
    </div>
  )
}

function formatMessageTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(date)
}
