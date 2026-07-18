import { X } from 'lucide-react'
import { useEffect, useId, useRef } from 'react'
import type { ReactNode } from 'react'

import { shouldCloseOverlay } from '../dialog/overlayState'
import styles from './Drawer.module.css'

export type DrawerSide = 'left' | 'right'

export type DrawerProps = {
  open: boolean
  title: string
  side: DrawerSide
  onClose: () => void
  children: ReactNode
  closeLabel: string
  pending?: boolean
  closeOnEscape?: boolean
  closeOnBackdrop?: boolean
  className?: string
  bodyClassName?: string
}

const focusableSelector = [
  'button:not([disabled])',
  'a[href]',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

export function Drawer({
  open,
  title,
  side,
  onClose,
  children,
  closeLabel,
  pending = false,
  closeOnEscape = true,
  closeOnBackdrop = true,
  className,
  bodyClassName
}: DrawerProps) {
  const titleId = useId()
  const panelRef = useRef<HTMLElement>(null)
  const previousFocusRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!open) return undefined
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const frame = window.requestAnimationFrame(() => {
      const firstFocusable = panelRef.current?.querySelector<HTMLElement>(focusableSelector)
      ;(firstFocusable ?? panelRef.current)?.focus()
    })

    return () => {
      window.cancelAnimationFrame(frame)
      previousFocusRef.current?.focus()
      previousFocusRef.current = null
    }
  }, [open])

  useEffect(() => {
    if (!open) return undefined
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && shouldCloseOverlay({ source: 'escape', pending, closeOnEscape, closeOnBackdrop })) {
        event.preventDefault()
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [closeOnBackdrop, closeOnEscape, onClose, open, pending])

  const layerClassName = [styles.layer, className ?? ''].filter(Boolean).join(' ')
  const panelClassName = [styles.drawer, side === 'right' ? styles.right : styles.left].join(' ')
  const bodyClassNames = [styles.body, bodyClassName ?? ''].filter(Boolean).join(' ')

  return (
    <div className={layerClassName} data-open={open ? 'true' : 'false'} aria-hidden={!open} inert={!open || undefined}>
      <button
        type="button"
        className={styles.backdrop}
        aria-label={closeLabel}
        tabIndex={open ? 0 : -1}
        disabled={pending || !closeOnBackdrop}
        onClick={() => {
          if (shouldCloseOverlay({ source: 'backdrop', pending, closeOnEscape, closeOnBackdrop })) onClose()
        }}
      />
      <aside ref={panelRef} className={panelClassName} data-side={side} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        <header className={styles.header}>
          <h2 id={titleId}>{title}</h2>
          <button type="button" aria-label={closeLabel} disabled={pending} onClick={onClose}>
            <X size={17} aria-hidden="true" />
          </button>
        </header>
        <div className={bodyClassNames}>{children}</div>
      </aside>
    </div>
  )
}
