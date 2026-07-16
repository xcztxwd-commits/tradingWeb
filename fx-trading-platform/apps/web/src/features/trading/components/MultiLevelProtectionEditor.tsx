import styles from './TradingControls.module.css'
import type { QuantityUnit } from '@fx-platform/shared-types'

export const MAX_PROTECTION_LEVELS = 10

export type ProtectionType = 'TAKE_PROFIT' | 'STOP_LOSS'
export type ProtectionExecutionType = 'MARKET' | 'LIMIT'

export type ProtectionLevel = {
  id: string
  protectionType: ProtectionType
  triggerPrice: string
  protectedQuantity: string
  executionType: ProtectionExecutionType
  limitPrice: string
}

type Props = {
  levels: readonly ProtectionLevel[]
  maxLevels?: number
  disabled?: boolean
  pending?: boolean
  quantityUnit?: QuantityUnit
  error?: string | null
  showQuantity?: boolean
  onLevelChange: (id: string, patch: Partial<Omit<ProtectionLevel, 'id'>>) => void
  onAdd: (type: ProtectionType) => void
  onRemove: (id: string) => void
}

export function MultiLevelProtectionEditor({
  levels,
  maxLevels = MAX_PROTECTION_LEVELS,
  disabled = false,
  pending = false,
  quantityUnit = 'BASE',
  error,
  showQuantity = true,
  onLevelChange,
  onAdd,
  onRemove
}: Props) {
  const levelLimit = Math.max(1, Math.min(MAX_PROTECTION_LEVELS, Math.floor(maxLevels)))
  const controlsDisabled = disabled || pending
  const addDisabled = controlsDisabled || levels.length >= levelLimit

  return (
    <section className={styles.protectionEditor} aria-label="Position take-profit and stop-loss protections" aria-busy={pending}>
      <header className={styles.controlHeader}>
        <span>
          <strong>TP / SL levels</strong>
          <small>
            {levels.length}/{levelLimit}
          </small>
        </span>
        <span className={styles.compactActions}>
          <button type="button" disabled={addDisabled} onClick={() => onAdd('TAKE_PROFIT')}>
            + TP
          </button>
          <button type="button" disabled={addDisabled} onClick={() => onAdd('STOP_LOSS')}>
            + SL
          </button>
        </span>
      </header>

      {levels.length === 0 ? <p className={styles.emptyControl}>No protection levels.</p> : null}

      <div className={styles.protectionLevels}>
        {levels.map((level, index) => (
          <fieldset key={level.id} className={styles.protectionLevel} disabled={controlsDisabled}>
            <legend>Level {index + 1}</legend>
            <label>
              <span>Type</span>
              <select
                value={level.protectionType}
                onChange={(event) => onLevelChange(level.id, { protectionType: event.target.value as ProtectionType })}
              >
                <option value="TAKE_PROFIT">Take profit</option>
                <option value="STOP_LOSS">Stop loss</option>
              </select>
            </label>
            <label>
              <span>Trigger price</span>
              <input
                inputMode="decimal"
                value={level.triggerPrice}
                onChange={(event) => onLevelChange(level.id, { triggerPrice: event.target.value })}
              />
            </label>
            {showQuantity ? (
              <label>
                <span>Quantity ({quantityUnit})</span>
                <input
                  inputMode="decimal"
                  value={level.protectedQuantity}
                  onChange={(event) => onLevelChange(level.id, { protectedQuantity: event.target.value })}
                />
              </label>
            ) : null}
            <label>
              <span>Execution</span>
              <select
                value={level.executionType}
                onChange={(event) => onLevelChange(level.id, { executionType: event.target.value as ProtectionExecutionType })}
              >
                <option value="MARKET">Market</option>
                <option value="LIMIT">Limit</option>
              </select>
            </label>
            {level.executionType === 'LIMIT' ? (
              <label>
                <span>Limit price</span>
                <input
                  inputMode="decimal"
                  value={level.limitPrice}
                  onChange={(event) => onLevelChange(level.id, { limitPrice: event.target.value })}
                />
              </label>
            ) : null}
            <button type="button" className={styles.removeAction} onClick={() => onRemove(level.id)}>
              Remove
            </button>
          </fieldset>
        ))}
      </div>

      {pending ? <span className={styles.controlStatus}>Saving protections…</span> : null}
      {error ? (
        <span className={styles.controlError} role="alert">
          {error}
        </span>
      ) : null}
    </section>
  )
}
