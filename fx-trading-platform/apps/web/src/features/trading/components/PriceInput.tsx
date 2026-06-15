import { useTranslation } from 'react-i18next'

type Props = {
  ariaLabel: string
  value: string
  unit: string
  error?: string
  bestPriceDisabled: boolean
  onBestPrice: () => void
  onChange: (value: string) => void
  onFocusChange?: (focused: boolean) => void
}

export function PriceInput({
  ariaLabel,
  value,
  unit,
  error,
  bestPriceDisabled,
  onBestPrice,
  onChange,
  onFocusChange
}: Props) {
  const { t } = useTranslation()

  return (
    <div className="trade-panel__price-row">
      <label className={`trade-panel__field ${error ? 'trade-panel__field--invalid' : ''}`}>
        <span className="trade-panel__field-label">{t('common.price')}</span>
        <span className="trade-panel__control">
          <input
            aria-label={ariaLabel}
            inputMode="decimal"
            placeholder="0.00"
            value={value}
            onBlur={() => onFocusChange?.(false)}
            onChange={(event) => onChange(event.target.value)}
            onFocus={() => onFocusChange?.(true)}
          />
          <span className="trade-panel__unit">{unit}</span>
        </span>
        {error ? <span className="trade-panel__error">{error}</span> : null}
      </label>
      <button
        type="button"
        className="trade-panel__best-price"
        disabled={bestPriceDisabled}
        onClick={onBestPrice}
      >
        {t('trading.bestPrice')}
      </button>
    </div>
  )
}
