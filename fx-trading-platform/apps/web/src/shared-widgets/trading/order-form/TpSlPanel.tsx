import { useTranslation } from 'react-i18next'

import type { TradeField, TradeFormState } from '@fx-platform/frontend-core'
import styles from './TradePanel.module.css'

type Props = {
  form: TradeFormState
  showErrors: boolean
  fieldErrors: Partial<Record<string, string>>
  onFieldChange: (field: TradeField, value: string | boolean) => void
}

type TpSlInput = {
  field: TradeField
  placeholder: string
  value: string
  unit?: string
  error?: string
}

export function TpSlPanel({ form, showErrors, fieldErrors, onFieldChange }: Props) {
  const { t } = useTranslation()
  const expanded = form.tpSlEnabled
  const buySide = form.side === 'buy'

  const handleToggle = (checked: boolean) => {
    onFieldChange('tpSlEnabled', checked)
    onFieldChange('takeProfitEnabled', checked)
    onFieldChange('stopLossEnabled', checked)
  }

  return (
    <section className={styles['trade-panel__tpsl']}>
      <div className={styles['trade-panel__tpsl-head']}>
        <label className={styles['trade-panel__check']}>
          <input type="checkbox" checked={expanded} onChange={(event) => handleToggle(event.target.checked)} />
          <span>{t('trading.takeProfitStopLoss')}</span>
        </label>
      </div>

      {expanded ? (
        <div className={styles['trade-panel__tpsl-expanded']}>
          <TpSlGroup
            title={buySide ? t('trading.takeProfit') : t('trading.bargainHunting')}
            inputs={[
              {
                field: 'takeProfitOrderPrice',
                placeholder: buySide ? t('trading.limitTakeProfit') : t('trading.limitBargainHunting'),
                value: form.takeProfitOrderPrice
              },
              {
                field: 'takeProfitTriggerPrice',
                placeholder: t('markets.changePercent'),
                value: form.takeProfitTriggerPrice,
                unit: '%',
                error: showErrors ? fieldErrors.takeProfitTriggerPrice : undefined
              }
            ]}
            onFieldChange={onFieldChange}
          />
          <TpSlGroup
            title={buySide ? t('trading.stopLoss') : t('trading.chaseUp')}
            inputs={[
              {
                field: 'stopLossTriggerPrice',
                placeholder: t('trading.stopLossTriggerPrice'),
                value: form.stopLossTriggerPrice,
                error: showErrors ? fieldErrors.stopLossTriggerPrice : undefined
              },
              {
                field: 'trailingCallbackRatio',
                placeholder: t('markets.changePercent'),
                value: form.trailingCallbackRatio,
                unit: '%'
              },
              {
                field: 'stopLossOrderPrice',
                placeholder: buySide ? t('trading.limitStopLoss') : t('trading.limitChaseUp'),
                value: form.stopLossOrderPrice
              }
            ]}
            onFieldChange={onFieldChange}
          />
        </div>
      ) : null}
    </section>
  )
}

function TpSlGroup({
  title,
  inputs,
  onFieldChange
}: {
  title: string
  inputs: TpSlInput[]
  onFieldChange: (field: TradeField, value: string | boolean) => void
}) {
  return (
    <div className={styles['trade-panel__tpsl-group']}>
      <span className={styles['trade-panel__tpsl-group-title']}>{title}</span>
      <div className={styles['trade-panel__tpsl-row']}>
        {inputs.slice(0, 2).map((input) => (
          <TradePanelInput key={input.field} input={input} onFieldChange={onFieldChange} />
        ))}
      </div>
      {inputs[2] ? <TradePanelInput input={inputs[2]} full onFieldChange={onFieldChange} /> : null}
    </div>
  )
}

function TradePanelInput({
  input,
  full = false,
  onFieldChange
}: {
  input: TpSlInput
  full?: boolean
  onFieldChange: (field: TradeField, value: string | boolean) => void
}) {
  return (
    <label className={`${styles['trade-panel__field']} ${full ? styles['trade-panel__field--full'] : ''} ${input.error ? styles['trade-panel__field--invalid'] : ''}`}>
      <span className={`${styles['trade-panel__control']} ${styles['trade-panel__control--compact']}`}>
        <input
          aria-label={input.placeholder}
          inputMode="decimal"
          placeholder={input.placeholder}
          value={input.value}
          onChange={(event) => onFieldChange(input.field, event.target.value)}
        />
        {input.unit ? <span className={`${styles['trade-panel__unit']} ${styles['trade-panel__unit--dropdown']}`}>{input.unit}</span> : null}
      </span>
      {input.error ? <span className={styles['trade-panel__error']}>{input.error}</span> : null}
    </label>
  )
}
