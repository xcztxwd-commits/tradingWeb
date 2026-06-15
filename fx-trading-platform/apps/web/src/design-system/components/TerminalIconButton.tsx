import type { ButtonHTMLAttributes, ReactNode } from 'react'

import styles from './TerminalIconButton.module.css'

type TerminalIconButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  icon: ReactNode
  label: string
  tone?: 'neutral' | 'primary'
}

export function TerminalIconButton({ icon, label, tone = 'neutral', className, ...buttonProps }: TerminalIconButtonProps) {
  const classNames = [styles.button, tone === 'primary' ? styles.primary : '', className ?? ''].filter(Boolean).join(' ')

  return (
    <button type="button" className={classNames} aria-label={label} title={label} {...buttonProps}>
      {icon}
    </button>
  )
}
