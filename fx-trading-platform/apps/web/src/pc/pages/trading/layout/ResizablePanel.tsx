import type { HTMLAttributes, ReactNode } from 'react'

import styles from './ResizablePanel.module.css'

type Props = HTMLAttributes<HTMLDivElement> & {
  children: ReactNode
  ariaLabel?: string
  className?: string
}

export function ResizablePanel({ children, ariaLabel, className, ...props }: Props) {
  return (
    <div aria-label={ariaLabel} className={className ? `${styles.panel} ${className}` : styles.panel} {...props}>
      {children}
    </div>
  )
}
