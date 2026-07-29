import { useTranslation } from 'react-i18next'

import styles from './TradePanel.module.css'

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
    <div className={styles['trade-panel__price-row']}>
      <label className={`${styles['trade-panel__field']} ${error ? styles['trade-panel__field--invalid'] : ''}`}>
        <span className={styles['trade-panel__control']}>
          <span className={styles['trade-panel__field-label']}>{t('common.price')}</span>
          <input
            aria-label={ariaLabel}
            inputMode="decimal"
            placeholder=""
            value={value}
            onBlur={() => onFocusChange?.(false)}
            onChange={(event) => onChange(event.target.value)}
            onFocus={() => onFocusChange?.(true)}
          />
          <span className={styles['trade-panel__unit']}>{unit}</span>
          <span className={styles['trade-panel__stepper']} aria-hidden="true"><span /><span /></span>
        </span>
        {error ? <span className={styles['trade-panel__error']}>{error}</span> : null}
      </label>
      <button
        type="button"
        className={styles['trade-panel__best-price']}
        disabled={bestPriceDisabled}
        onClick={onBestPrice}
      >
        {t('trading.bestPrice')}
      </button>
    </div>
  )
}
