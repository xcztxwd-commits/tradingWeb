import { Drawer } from '@fx-platform/ui'
import { X } from 'lucide-react'
import type { ReactNode } from 'react'

import styles from './MobilePanels.module.css'

type DrawerProps = {
  open: boolean
  title: string
  side: 'left' | 'right'
  onClose: () => void
  children: ReactNode
}

type SheetProps = {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
}

export function MobileDrawer({ open, title, side, onClose, children }: DrawerProps) {
  return (
    <Drawer
      open={open}
      title={title}
      side={side}
      onClose={onClose}
      closeLabel={`Close ${title}`}
      className={styles.drawerViewport}
    >
      {children}
    </Drawer>
  )
}

export function MobileOrderSheet({ open, title, onClose, children }: SheetProps) {
  return (
    <div className={`${styles.sheetLayer} ${open ? styles.open : ''}`} aria-hidden={!open} inert={!open || undefined}>
      <button type="button" className={styles.backdrop} tabIndex={open ? 0 : -1} onClick={onClose} />
      <section className={styles.sheet} role="dialog" aria-modal="true" aria-labelledby="mobile-order-sheet-title">
        <header className={styles.mobileHeader}>
          <h2 id="mobile-order-sheet-title">{title}</h2>
          <button type="button" onClick={onClose}>
            <X size={17} />
          </button>
        </header>
        <div className={styles.sheetBody}>{children}</div>
      </section>
    </div>
  )
}
