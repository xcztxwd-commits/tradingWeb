import type { ChangeEventHandler, FocusEventHandler, ReactNode } from 'react'

import styles from './TradingInput.module.css'

type Props = {
  label: string
  value: string
  placeholder?: string
  prefix?: ReactNode
  suffix?: ReactNode
  error?: string
  disabled?: boolean
  inputMode?: 'decimal' | 'numeric' | 'text'
  onChange: (value: string) => void
  onFocus?: FocusEventHandler<HTMLInputElement>
  onBlur?: FocusEventHandler<HTMLInputElement>
}

export function TradingInput({
  label,
  value,
  placeholder,
  prefix,
  suffix,
  error,
  disabled = false,
  inputMode = 'decimal',
  onChange,
  onFocus,
  onBlur
}: Props) {
  const handleChange: ChangeEventHandler<HTMLInputElement> = (event) => {
    onChange(event.target.value)
  }

  return (
    <label className={`${styles.field} ${error ? styles.invalid : ''} ${disabled ? styles.disabled : ''}`}>
      <span className={styles.label}>{label}</span>
      <span className={styles.control}>
        {prefix ? <span className={styles.affix}>{prefix}</span> : null}
        <input
          value={value}
          placeholder={placeholder}
          disabled={disabled}
          inputMode={inputMode}
          onBlur={onBlur}
          onChange={handleChange}
          onFocus={onFocus}
        />
        {suffix ? <span className={styles.affix}>{suffix}</span> : null}
      </span>
      {error ? <span className={styles.error}>{error}</span> : null}
    </label>
  )
}
