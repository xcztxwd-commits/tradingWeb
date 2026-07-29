import { useTranslation } from 'react-i18next'

import { advancedLimitModes, type TradeField, type TradeFormState } from '@fx-platform/frontend-core'
import styles from './TradePanel.module.css'

type Props = {
  form: TradeFormState
  showErrors: boolean
  fieldErrors: Partial<Record<string, string>>
  onFieldChange: (field: TradeField, value: string) => void
}

export function AdvancedOrderPanel({ form, showErrors, fieldErrors, onFieldChange }: Props) {
  const { t } = useTranslation()
  const quoteUnit = form.symbol.endsWith('USDT') ? 'USDT' : ''

  if (form.strategyType === 'advanced_limit') {
    return (
      <section className={styles['trade-panel__advanced']}>
        <span className={styles['trade-panel__mini-title']}>{t('trading.advancedLimitMode')}</span>
        <div className={styles['trade-panel__mode-grid']}>
          {advancedLimitModes.map((mode) => (
            <button
              key={mode.value}
              type="button"
              className={form.advancedLimitMode === mode.value ? styles['trade-panel__mode--active'] : ''}
              onClick={() => onFieldChange('advancedLimitMode', mode.value)}
            >
              {t(mode.labelKey)}
            </button>
          ))}
        </div>
      </section>
    )
  }

  if (form.strategyType === 'trailing_tp_sl') {
    return (
      <section className={styles['trade-panel__advanced']}>
        <span className={styles['trade-panel__mini-title']}>{t('trading.strategy.trailingTpSl')}</span>
        <StrategyInput
          label={t('trading.callbackRatio')}
          unit="%"
          value={form.trailingCallbackRatio}
          error={showErrors ? fieldErrors.trailingCallbackRatio : undefined}
          onChange={(value) => onFieldChange('trailingCallbackRatio', value)}
        />
        <StrategyInput
          label={t('trading.activationPrice')}
          unit={quoteUnit}
          value={form.trailingActivationPrice}
          onChange={(value) => onFieldChange('trailingActivationPrice', value)}
        />
      </section>
    )
  }

  if (form.strategyType === 'trigger') {
    return (
      <section className={styles['trade-panel__advanced']}>
        <span className={styles['trade-panel__mini-title']}>{t('trading.strategy.trigger')}</span>
        <StrategyInput
          label={t('trading.triggerPrice')}
          unit={quoteUnit}
          value={form.triggerPrice}
          error={showErrors ? fieldErrors.triggerPrice : undefined}
          onChange={(value) => onFieldChange('triggerPrice', value)}
        />
      </section>
    )
  }

  return null
}

type StrategyInputProps = {
  label: string
  value: string
  unit: string
  error?: string
  onChange: (value: string) => void
}

function StrategyInput({ label, value, unit, error, onChange }: StrategyInputProps) {
  return (
    <label className={`${styles['trade-panel__field']} ${error ? styles['trade-panel__field--invalid'] : ''}`}>
      <span className={`${styles['trade-panel__control']} ${styles['trade-panel__control--compact']}`}>
        <input
          aria-label={label}
          inputMode="decimal"
          placeholder={label}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
        {unit ? <span className={styles['trade-panel__unit']}>{unit}</span> : null}
      </span>
      {error ? <span className={styles['trade-panel__error']}>{error}</span> : null}
    </label>
  )
}
