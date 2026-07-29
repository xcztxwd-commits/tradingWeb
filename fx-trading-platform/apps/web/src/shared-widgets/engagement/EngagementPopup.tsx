import { Dialog, getCriticalDialogOpen } from '@fx-platform/ui'
import { X } from 'lucide-react'
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'

import type { PopupDisplayModel, PopupQueueSnapshot } from '@fx-platform/frontend-core'
import {
  activateEngagementPopupCta,
  createEngagementPopupView
} from './engagementPopupModel.ts'
import styles from './EngagementPopup.module.css'

type PopupDirective = 'CONTINUE' | 'TERMINATE' | null

export type EngagementPopupActions = Readonly<{
  popupMounted(): Promise<void>
  closeCurrent(): Promise<PopupDirective>
  clickCurrent(): Promise<PopupDirective>
  optOutCurrent(): Promise<PopupDirective>
}>

export type EngagementPopupLabels = Readonly<{
  close: string
  optOut: string
}>

export type EngagementPopupProps = Readonly<{
  snapshot: PopupQueueSnapshot<PopupDisplayModel>
  actions: EngagementPopupActions
  labels: EngagementPopupLabels
  navigate: (route: string) => void
}>

const panelSizes = {
  SMALL: 'panelSmall',
  MEDIUM: 'panelMedium',
  LARGE: 'panelLarge'
} as const

export function EngagementPopup({ snapshot, actions, labels, navigate }: EngagementPopupProps) {
  const view = useMemo(() => createEngagementPopupView(snapshot), [snapshot])
  const titleId = useId()
  const closeButtonRef = useRef<HTMLButtonElement>(null)
  const pendingRef = useRef(false)
  const [pending, setPending] = useState(false)

  useEffect(() => {
    if (!view || snapshot.blocked || getCriticalDialogOpen()) return
    void actions.popupMounted().catch(() => undefined)
  }, [actions, snapshot.blocked, view?.deliveryId])

  const runExclusive = useCallback(async <T,>(action: () => Promise<T>): Promise<T | null> => {
    if (pendingRef.current) return null
    pendingRef.current = true
    setPending(true)
    try {
      return await action()
    } catch {
      return null
    } finally {
      pendingRef.current = false
      setPending(false)
    }
  }, [])

  const handleClose = useCallback(() => {
    void runExclusive(() => actions.closeCurrent())
  }, [actions, runExclusive])

  const handleOptOut = useCallback(() => {
    void runExclusive(() => actions.optOutCurrent())
  }, [actions, runExclusive])

  const handleCta = useCallback(() => {
    if (!view?.cta) return
    void runExclusive(() => activateEngagementPopupCta(view.cta, actions.clickCurrent, navigate))
  }, [actions, navigate, runExclusive, view?.cta])

  if (!view) return null

  const panelClassName = `${styles.panel} ${styles[panelSizes[view.sizeMode]]}`
  const layerClassName = `${styles.layer} ${view.surface === 'MOBILE' ? styles.mobileLayer : ''}`

  return (
    <Dialog
      open
      onClose={handleClose}
      labelledBy={titleId}
      closeLabel={labels.close}
      pending={pending}
      priority="standard"
      initialFocusRef={closeButtonRef}
      className={layerClassName}
      panelClassName={panelClassName}
    >
      <header className={styles.header}>
        <h2 id={titleId}>{view.title}</h2>
        <button
          ref={closeButtonRef}
          type="button"
          className={styles.close}
          aria-label={labels.close}
          disabled={pending}
          onClick={handleClose}
        >
          <X size={20} aria-hidden="true" />
        </button>
      </header>

      {view.coverUrl ? <img className={styles.cover} src={view.coverUrl} alt="" /> : null}

      <div className={styles.body} dangerouslySetInnerHTML={{ __html: view.html }} />

      <footer className={styles.actions}>
        <button type="button" className={styles.optOut} disabled={pending} onClick={handleOptOut}>
          {labels.optOut}
        </button>
        <button type="button" className={styles.dismiss} disabled={pending} onClick={handleClose}>
          {labels.close}
        </button>
        {view.cta ? (
          <button type="button" className={styles.cta} disabled={pending} onClick={handleCta}>
            {view.cta.label}
          </button>
        ) : null}
      </footer>
    </Dialog>
  )
}
