import { Dialog, Drawer } from '@fx-platform/ui'
import { X } from 'lucide-react'
import { useId, type ReactNode } from 'react'

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
  const titleId = useId()

  return (
    <Dialog
      open={open}
      onClose={onClose}
      labelledBy={titleId}
      closeLabel={`Close ${title}`}
      className={styles.sheetLayer}
      backdropClassName={styles.backdrop}
      panelClassName={styles.sheet}
    >
      <header className={styles.mobileHeader}>
        <h2 id={titleId}>{title}</h2>
        <button type="button" aria-label={`Close ${title}`} onClick={onClose}>
          <X size={17} aria-hidden="true" />
        </button>
      </header>
      <div className={styles.sheetBody}>{children}</div>
    </Dialog>
  )
}
