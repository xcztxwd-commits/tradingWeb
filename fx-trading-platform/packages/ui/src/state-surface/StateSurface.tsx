import { Skeleton } from '../skeleton/Skeleton'
import styles from './StateSurface.module.css'

export type StateSurfaceVariant = 'default' | 'empty' | 'login' | 'error' | 'loading'

export type StateSurfaceProps = {
  title: string
  message?: string
  detail?: string
  actionLabel?: string
  onAction?: () => void
  variant?: StateSurfaceVariant
}

export function StateSurface({
  title,
  message,
  detail,
  actionLabel,
  onAction,
  variant = 'default'
}: StateSurfaceProps) {
  const role = variant === 'error' ? 'alert' : variant === 'loading' ? 'status' : undefined
  const classNames = [styles.surface, styles[variant]].filter(Boolean).join(' ')

  return (
    <section className={classNames} role={role} aria-live={variant === 'loading' ? 'polite' : undefined} data-state-variant={variant}>
      <div className={styles.content}>
        <strong>{title}</strong>
        {message ? <span>{message}</span> : null}
        {detail ? <small>{detail}</small> : null}
      </div>
      {variant === 'loading' ? (
        <div className={styles.skeletons} aria-hidden="true">
          <Skeleton width="100%" />
          <Skeleton width="82%" />
          <Skeleton width="62%" />
        </div>
      ) : null}
      {actionLabel ? (
        <button type="button" className={styles.action} onClick={onAction}>
          {actionLabel}
        </button>
      ) : null}
    </section>
  )
}
