type Props = {
  ariaLabel: string
  label: string
  value: string
  unit: string
  caption?: string
  placeholder?: string
  error?: string
  onChange: (value: string) => void
}

export function AmountInput({ ariaLabel, label, value, unit, caption, placeholder = '0.00', error, onChange }: Props) {
  return (
    <label className={`trade-panel__field ${error ? 'trade-panel__field--invalid' : ''}`}>
      <span className="trade-panel__control">
        <span className="trade-panel__field-label">{label}</span>
        <input
          aria-label={ariaLabel}
          inputMode="decimal"
          placeholder={placeholder}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
        {caption ? <span className="trade-panel__quantity-caption">{caption}</span> : null}
        <span className="trade-panel__unit">{unit}</span>
      </span>
      {error ? <span className="trade-panel__error">{error}</span> : null}
    </label>
  )
}
