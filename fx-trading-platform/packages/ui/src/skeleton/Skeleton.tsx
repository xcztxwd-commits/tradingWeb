import type { CSSProperties, HTMLAttributes } from 'react'

import styles from './Skeleton.module.css'

export type SkeletonShape = 'line' | 'block' | 'circle'

export type SkeletonProps = Omit<HTMLAttributes<HTMLSpanElement>, 'children'> & {
  shape?: SkeletonShape
  width?: string | number
  height?: string | number
}

type SkeletonStyle = CSSProperties & {
  '--skeleton-width'?: string
  '--skeleton-height'?: string
}

function toCssSize(value: string | number | undefined) {
  if (value === undefined) return undefined
  return typeof value === 'number' ? `${value}px` : value
}

export function Skeleton({ shape = 'line', width, height, className, style, ...spanProps }: SkeletonProps) {
  const dimensions: SkeletonStyle = {
    ...style,
    '--skeleton-width': toCssSize(width),
    '--skeleton-height': toCssSize(height)
  }
  const classNames = [styles.skeleton, styles[shape], className ?? ''].filter(Boolean).join(' ')

  return <span {...spanProps} className={classNames} style={dimensions} aria-hidden="true" />
}
