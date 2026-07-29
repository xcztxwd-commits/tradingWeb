import styles from './TradePanel.module.css'

type Props = {
  ariaLabel: string
  label: string
  value: string
  unit: string
  caption?: string
  placeholder?: string
  error?: string
  showStepper?: boolean
  unitDropdown?: boolean
  onChange: (value: string) => void
}

export function AmountInput({
  ariaLabel,
  label,
  value,
  unit,
  caption,
  placeholder,
  error,
  showStepper = true,
  unitDropdown = false,
  onChange
}: Props) {
  return (
    <label className={`${styles['trade-panel__field']} ${error ? styles['trade-panel__field--invalid'] : ''}`}>
      <span className={styles['trade-panel__control']}>
        <span className={styles['trade-panel__field-label']}>{label}</span>
        <input
          aria-label={ariaLabel}
          inputMode="decimal"
          placeholder={placeholder ?? ''}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
        {caption ? <span className={styles['trade-panel__quantity-caption']}>{caption}</span> : null}
        <span className={`${styles['trade-panel__unit']} ${unitDropdown ? styles['trade-panel__unit--dropdown'] : ''}`}>{unit}</span>
        {showStepper ? <span className={styles['trade-panel__stepper']} aria-hidden="true"><span /><span /></span> : null}
      </span>
      {error ? <span className={styles['trade-panel__error']}>{error}</span> : null}
    </label>
  )
}
