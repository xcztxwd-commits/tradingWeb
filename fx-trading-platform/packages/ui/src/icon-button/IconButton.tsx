import type { ButtonHTMLAttributes, ReactNode } from 'react'

import styles from './IconButton.module.css'

export type IconButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  icon: ReactNode
  label: string
  tone?: 'neutral' | 'primary'
}

export function IconButton({ icon, label, tone = 'neutral', className, title, ...buttonProps }: IconButtonProps) {
  const classNames = [styles.button, tone === 'primary' ? styles.primary : '', className ?? ''].filter(Boolean).join(' ')

  return (
    <button {...buttonProps} type="button" className={classNames} aria-label={label} title={title ?? label}>
      {icon}
    </button>
  )
}
