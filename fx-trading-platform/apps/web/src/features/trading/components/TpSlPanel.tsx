import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'

import type { TradeField, TradeFormState } from '../types/order'
import { formatDecimal } from '../utils/format'

type Props = {
  form: TradeFormState
  showErrors: boolean
  fieldErrors: Partial<Record<string, string>>
  onFieldChange: (field: TradeField, value: string | boolean) => void
}

type TriggerNote = {
  text: string
  tone: 'positive' | 'negative' | 'neutral'
}

export function TpSlPanel({ form, showErrors, fieldErrors, onFieldChange }: Props) {
  const { t } = useTranslation()
  const expanded = form.tpSlEnabled || form.strategyType === 'tp_sl'
  const takeProfitError = showErrors ? fieldErrors.takeProfitTriggerPrice : undefined
  const stopLossError = showErrors ? fieldErrors.stopLossTriggerPrice : undefined
  const quoteAsset = form.symbol.endsWith('USDT') ? 'USDT' : ''

  const updateTakeProfit = (value: string) => {
    if (value && !form.takeProfitEnabled) onFieldChange('takeProfitEnabled', true)
    onFieldChange('takeProfitTriggerPrice', value)
  }

  const updateStopLoss = (value: string) => {
    if (value && !form.stopLossEnabled) onFieldChange('stopLossEnabled', true)
    onFieldChange('stopLossTriggerPrice', value)
  }

  return (
    <section className="trade-panel__tpsl">
      <div className="trade-panel__tpsl-head">
        <label className="trade-panel__check">
          <input
            type="checkbox"
            checked={form.tpSlEnabled}
            onChange={(event) => onFieldChange('tpSlEnabled', event.target.checked)}
          />
          <span>{t('trading.takeProfitStopLoss')}</span>
        </label>
        <span className="trade-panel__advanced-link">{t('trading.advanced')} ›</span>
      </div>

      {expanded ? (
        <div className="trade-panel__tpsl-grid">
          <TriggerInput
            label={t('trading.takeProfitPrice')}
            unit={quoteAsset}
            value={form.takeProfitTriggerPrice}
            error={takeProfitError}
            note={buildTriggerNote('takeProfit', form, t)}
            onChange={updateTakeProfit}
          />
          <TriggerInput
            label={t('trading.stopLossPrice')}
            unit={quoteAsset}
            value={form.stopLossTriggerPrice}
            error={stopLossError}
            note={buildTriggerNote('stopLoss', form, t)}
            onChange={updateStopLoss}
          />
        </div>
      ) : null}
    </section>
  )
}

type TriggerInputProps = {
  label: string
  value: string
  unit: string
  note: TriggerNote
  error?: string
  onChange: (value: string) => void
}

function TriggerInput({ label, value, unit, note, error, onChange }: TriggerInputProps) {
  const { t } = useTranslation()

  return (
    <label className={`trade-panel__field ${error ? 'trade-panel__field--invalid' : ''}`}>
      <span className="trade-panel__control trade-panel__control--compact trade-panel__tpsl-order-row">
        <span className="trade-panel__field-label">{label}</span>
        <input
          aria-label={label}
          inputMode="decimal"
          placeholder={label}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
        {unit ? <span className="trade-panel__unit">{unit}</span> : null}
        <span className="trade-panel__latest-price">{t('trading.latestPrice')}</span>
      </span>
      {note.text ? (
        <span className={`trade-panel__tpsl-trigger-note trade-panel__tpsl-trigger-note--${note.tone}`}>{note.text}</span>
      ) : null}
      {error ? <span className="trade-panel__error">{error}</span> : null}
    </label>
  )
}

function buildTriggerNote(kind: 'takeProfit' | 'stopLoss', form: TradeFormState, t: TFunction): TriggerNote {
  const triggerValue = Number(kind === 'takeProfit' ? form.takeProfitTriggerPrice : form.stopLossTriggerPrice)
  const amount = Number(form.amount)
  const reference = Number(form.price)

  if (!Number.isFinite(triggerValue) || triggerValue <= 0) return { text: '', tone: 'neutral' as const }

  const formattedTrigger = `${triggerValue.toLocaleString(undefined, { maximumFractionDigits: 8 })} USDT`
  if (!Number.isFinite(amount) || amount <= 0 || !Number.isFinite(reference) || reference <= 0) {
    return {
      text: t('trading.triggerMarketNote', { price: formattedTrigger }),
      tone: 'neutral' as const
    }
  }

  const direction = form.side === 'buy' ? 1 : -1
  const estimatedPnl = (triggerValue - reference) * amount * direction
  const tone: TriggerNote['tone'] = estimatedPnl >= 0 ? 'positive' : 'negative'
  const sign = estimatedPnl >= 0 ? '+' : ''
  const pnlText = `${sign}${formatDecimal(estimatedPnl) || estimatedPnl.toFixed(2)} USDT`

  return {
    text: t('trading.triggerMarketPnlNote', { price: formattedTrigger, pnl: pnlText }),
    tone
  }
}
