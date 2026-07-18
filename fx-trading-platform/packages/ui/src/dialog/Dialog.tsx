import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'

import styles from './Dialog.module.css'
import { shouldCloseOverlay } from './overlayState'

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
  className?: string
  backdropClassName?: string
  panelClassName?: string
}

const focusableSelector = [
  'button:not([disabled])',
  'a[href]',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

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
  className,
  backdropClassName,
  panelClassName
}: DialogProps) {
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

  if (!open) return null

  const layerClassName = [styles.layer, className ?? ''].filter(Boolean).join(' ')
  const backdropClassNames = [styles.backdrop, backdropClassName ?? ''].filter(Boolean).join(' ')
  const panelClassNames = [styles.panel, panelClassName ?? ''].filter(Boolean).join(' ')

  return (
    <div className={layerClassName} role="presentation">
      <button
        type="button"
        className={backdropClassNames}
        aria-label={closeLabel}
        disabled={pending || !closeOnBackdrop}
        onClick={() => {
          if (shouldCloseOverlay({ source: 'backdrop', pending, closeOnEscape, closeOnBackdrop })) onClose()
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
