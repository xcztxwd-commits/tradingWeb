import { useEffect, useState } from 'react'

import styles from './HomeContent.module.css'

type AnimatedCounterProps = {
  value: number
  label?: string
}

export function AnimatedCounter({ value, label }: AnimatedCounterProps) {
  const [ticking, setTicking] = useState(false)

  useEffect(() => {
    setTicking(true)
    const timer = window.setTimeout(() => setTicking(false), 260)
    return () => window.clearTimeout(timer)
  }, [value])

  return (
    <div className={ticking ? styles.counterTick : undefined}>
      <strong>{value.toLocaleString('en-US')}</strong>
      {label ? <span>{label}</span> : null}
    </div>
  )
}
