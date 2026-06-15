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
    <div className={`${styles.drawerLayer} ${open ? styles.open : ''}`} aria-hidden={!open}>
      <button type="button" className={styles.backdrop} tabIndex={open ? 0 : -1} onClick={onClose} />
      <aside className={`${styles.drawer} ${side === 'right' ? styles.right : styles.left}`}>
        <header className={styles.mobileHeader}>
          <h2>{title}</h2>
          <button type="button" onClick={onClose}>
            <X size={17} />
          </button>
        </header>
        <div className={styles.drawerBody}>{children}</div>
      </aside>
    </div>
  )
}

export function MobileOrderSheet({ open, title, onClose, children }: SheetProps) {
  return (
    <div className={`${styles.sheetLayer} ${open ? styles.open : ''}`} aria-hidden={!open}>
      <button type="button" className={styles.backdrop} tabIndex={open ? 0 : -1} onClick={onClose} />
      <section className={styles.sheet}>
        <header className={styles.mobileHeader}>
          <h2>{title}</h2>
          <button type="button" onClick={onClose}>
            <X size={17} />
          </button>
        </header>
        <div className={styles.sheetBody}>{children}</div>
      </section>
    </div>
  )
}
