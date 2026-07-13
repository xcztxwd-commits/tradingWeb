import { MultiLevelProtectionEditor, type ProtectionLevel } from './MultiLevelProtectionEditor'
import type { TradeField, TradeFormState } from '../types/order'

type Props = {
  form: TradeFormState
  positionMode: 'ONE_WAY' | 'HEDGE'
  disabled?: boolean
  error?: string
  onFieldChange: (field: TradeField, value: string | number | boolean) => void
  onProtectionsChange: (protections: TradeFormState['attachedProtections']) => void
}

export function PerpetualOrderOptions({
  form,
  positionMode,
  disabled = false,
  error,
  onFieldChange,
  onProtectionsChange
}: Props) {
  const levels: ProtectionLevel[] = form.attachedProtections.map((protection, index) => ({
    id: String(index),
    protectionType: protection.protectionType,
    triggerPrice: String(protection.triggerPrice),
    protectedQuantity: form.amount,
    executionType: protection.triggerExecutionType,
    limitPrice: protection.price === undefined ? '' : String(protection.price)
  }))

  const updateLevel = (id: string, patch: Partial<Omit<ProtectionLevel, 'id'>>) => {
    const index = Number(id)
    onProtectionsChange(form.attachedProtections.map((protection, current) => current === index ? {
      ...protection,
      protectionType: patch.protectionType ?? protection.protectionType,
      triggerPrice: patch.triggerPrice === undefined ? protection.triggerPrice : Number(patch.triggerPrice),
      triggerExecutionType: patch.executionType ?? protection.triggerExecutionType,
      price: (patch.executionType ?? protection.triggerExecutionType) === 'LIMIT'
        ? Number(patch.limitPrice ?? protection.price ?? 0)
        : undefined
    } : protection))
  }

  const addLevel = (protectionType: ProtectionLevel['protectionType']) => {
    if (form.attachedProtections.length >= 10) return
    onProtectionsChange([...form.attachedProtections, {
      protectionType,
      triggerPrice: 0,
      triggerPriceType: 'MARK_PRICE',
      triggerExecutionType: 'MARKET'
    }])
  }

  return (
    <section className="trade-panel__perpetual-options" aria-label="Perpetual order options">
      {positionMode === 'HEDGE' ? (
        <label className="trade-panel__field">
          <span className="trade-panel__field-label">Position side</span>
          <select value={form.positionSide === 'BOTH' ? (form.side === 'buy' ? 'LONG' : 'SHORT') : form.positionSide} disabled={disabled} onChange={(event) => onFieldChange('positionSide', event.target.value)}>
            <option value="LONG">Long</option>
            <option value="SHORT">Short</option>
          </select>
        </label>
      ) : null}
      <label className="trade-panel__check">
        <input type="checkbox" checked={form.reduceOnly} disabled={disabled} onChange={(event) => onFieldChange('reduceOnly', event.target.checked)} />
        <span>Reduce only</span>
      </label>
      <MultiLevelProtectionEditor
        levels={levels}
        disabled={disabled}
        error={error}
        showQuantity={false}
        onLevelChange={updateLevel}
        onAdd={addLevel}
        onRemove={(id) => onProtectionsChange(form.attachedProtections.filter((_, index) => index !== Number(id)))}
      />
    </section>
  )
}
