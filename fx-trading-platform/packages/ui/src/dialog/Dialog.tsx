import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode, RefObject } from 'react'

import styles from './Dialog.module.css'
import {
  focusInitialElement,
  isTopDialogOverlay,
  lockBodyScroll,
  registerDialogOverlay,
  shouldCloseOverlay,
  trapTabKey,
  updateDialogOverlayPriority
} from './overlayState'
import type { OverlayPriority, OverlaySnapshot } from './overlayState'

export type DialogPriority = OverlayPriority

const useBrowserLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect

export type DialogProps = {
  open: boolean
  onClose: () => void
  children: ReactNode
  labelledBy?: string
  ariaLabel?: string
  closeLabel: string
  pending?: boolean
  closeOnEscape?: boolean
  closeOnBackdrop?: boolean
  priority?: DialogPriority
  initialFocusRef?: RefObject<HTMLElement | null>
  className?: string
  backdropClassName?: string
  panelClassName?: string
}

export function Dialog({
  open,
  onClose,
  children,
  labelledBy,
  ariaLabel,
  closeLabel,
  pending = false,
  closeOnEscape = true,
  closeOnBackdrop = true,
  priority = 'standard',
  initialFocusRef,
  className,
  backdropClassName,
  panelClassName
}: DialogProps) {
  const panelRef = useRef<HTMLElement>(null)
  const previousFocusRef = useRef<HTMLElement | null>(null)
  const initialFocusRefRef = useRef(initialFocusRef)
  const priorityRef = useRef(priority)
  const overlayIdRef = useRef(Symbol('dialog'))
  const [overlaySnapshot, setOverlaySnapshot] = useState<OverlaySnapshot>({ layer: 0, top: true })
  initialFocusRefRef.current = initialFocusRef
  priorityRef.current = priority

  useBrowserLayoutEffect(() => {
    if (!open || typeof document === 'undefined') return undefined
    const registeredPanel = panelRef.current
    const ownerDocument = registeredPanel?.ownerDocument ?? document
    previousFocusRef.current = isFocusableTarget(ownerDocument.activeElement)
      ? ownerDocument.activeElement
      : null
    const activate = (preferredFocus: HTMLElement | null) => {
      const panel = panelRef.current
      if (!panel) return
      if (preferredFocus && panel.contains(preferredFocus)) {
        preferredFocus.focus()
        return
      }
      focusInitialElement(panel, initialFocusRefRef.current?.current ?? null)
    }
    const unregister = registerDialogOverlay(
      overlayIdRef.current,
      priorityRef.current,
      setOverlaySnapshot,
      activate,
      previousFocusRef.current,
      (target) => registeredPanel?.contains(target) ?? false,
      ownerDocument
    )
    const unlockBodyScroll = lockBodyScroll(ownerDocument, registeredPanel)
    const cancelInitialFocus = isTopDialogOverlay(overlayIdRef.current)
      ? onNextFrame(ownerDocument, () => activate(null))
      : () => undefined

    return () => {
      cancelInitialFocus()
      const removal = unregister()
      unlockBodyScroll()
      const previousFocus = removal.restoreFocus ?? null
      previousFocusRef.current = null
      if (!removal.wasTop) return
      onNextFrame(ownerDocument, () => {
        if (removal.activateNext) {
          removal.activateNext(previousFocus)
          return
        }
        if (previousFocus?.isConnected !== false) previousFocus?.focus()
      })
    }
  }, [open])

  useBrowserLayoutEffect(() => {
    if (!open) return
    const activateTop = updateDialogOverlayPriority(overlayIdRef.current, priority)
    if (!activateTop || typeof document === 'undefined') return
    const ownerDocument = panelRef.current?.ownerDocument ?? document
    return onNextFrame(ownerDocument, () => activateTop(null))
  }, [open, priority])

  useEffect(() => {
    if (!open || typeof document === 'undefined') return undefined
    const ownerDocument = panelRef.current?.ownerDocument ?? document
    const handleKeyDown = (event: KeyboardEvent) => {
      if (!isTopDialogOverlay(overlayIdRef.current)) return
      if (event.key === 'Tab' && panelRef.current) {
        trapTabKey(event, panelRef.current)
        return
      }
      if (event.key !== 'Escape') return
      event.stopPropagation()
      event.preventDefault()
      if (!shouldCloseOverlay({ source: 'escape', pending, closeOnEscape, closeOnBackdrop })) return
      onClose()
    }
    ownerDocument.addEventListener('keydown', handleKeyDown, true)
    return () => ownerDocument.removeEventListener('keydown', handleKeyDown, true)
  }, [closeOnBackdrop, closeOnEscape, onClose, open, pending])

  if (!open) return null

  const layerClassName = [styles.layer, className ?? ''].filter(Boolean).join(' ')
  const backdropClassNames = [styles.backdrop, backdropClassName ?? ''].filter(Boolean).join(' ')
  const panelClassNames = [styles.panel, panelClassName ?? ''].filter(Boolean).join(' ')

  return (
    <div
      className={layerClassName}
      role="presentation"
      data-overlay-priority={priority}
      data-overlay-top={overlaySnapshot.top ? 'true' : 'false'}
      aria-hidden={!overlaySnapshot.top || undefined}
      inert={!overlaySnapshot.top || undefined}
      style={{ zIndex: `calc(var(--z-modal, 1200) + ${overlaySnapshot.layer})` }}
    >
      <button
        type="button"
        className={backdropClassNames}
        aria-label={closeLabel}
        disabled={pending || !closeOnBackdrop || !overlaySnapshot.top}
        onClick={() => {
          if (
            isTopDialogOverlay(overlayIdRef.current)
            && shouldCloseOverlay({ source: 'backdrop', pending, closeOnEscape, closeOnBackdrop })
          ) onClose()
        }}
      />
      <section
        ref={panelRef}
        className={panelClassNames}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        aria-label={labelledBy ? undefined : ariaLabel}
        aria-busy={pending || undefined}
        tabIndex={-1}
      >
        {children}
      </section>
    </div>
  )
}

function isFocusableTarget(value: Element | null): value is HTMLElement {
  return Boolean(value && 'focus' in value && typeof value.focus === 'function')
}

function onNextFrame(ownerDocument: Document, callback: () => void) {
  const ownerWindow = ownerDocument.defaultView
  if (ownerWindow?.requestAnimationFrame) {
    const frame = ownerWindow.requestAnimationFrame(callback)
    return () => ownerWindow.cancelAnimationFrame(frame)
  }
  const timeout = setTimeout(callback, 0)
  return () => clearTimeout(timeout)
}
