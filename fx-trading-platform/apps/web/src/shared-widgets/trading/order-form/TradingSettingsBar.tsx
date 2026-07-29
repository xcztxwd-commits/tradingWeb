import type { ChangeEvent } from 'react'

import styles from './TradingControls.module.css'

export type PositionMode = 'ONE_WAY' | 'HEDGE'
export type MarginMode = 'CROSS' | 'ISOLATED'
export type QuantityUnit = 'BASE' | 'QUOTE' | 'CONTRACTS'
export type TradingSettingField = 'positionMode' | 'marginMode' | 'leverage' | 'quantityUnit'

const DEFAULT_QUANTITY_UNITS: readonly QuantityUnit[] = ['BASE', 'QUOTE', 'CONTRACTS']

type Props = {
  positionMode: PositionMode
  marginMode: MarginMode
  leverage: number
  maxLeverage: number
  quantityUnit: QuantityUnit
  quantityUnitOptions?: readonly QuantityUnit[]
  disabled?: boolean
  pending?: boolean
  pendingSetting?: TradingSettingField | null
  error?: string | null
  onPositionModeChange: (value: PositionMode) => void
  onMarginModeChange: (value: MarginMode) => void
  onLeverageChange: (value: number) => void
  onQuantityUnitChange: (value: QuantityUnit) => void
}

function clampLeverage(value: number, backendMaximum: number) {
  const maximum = Math.max(1, Math.min(100, Math.floor(backendMaximum)))
  return Math.max(1, Math.min(maximum, Math.round(value)))
}

export function TradingSettingsBar({
  positionMode,
  marginMode,
  leverage,
  maxLeverage,
  quantityUnit,
  quantityUnitOptions = DEFAULT_QUANTITY_UNITS,
  disabled = false,
  pending = false,
  pendingSetting = null,
  error,
  onPositionModeChange,
  onMarginModeChange,
  onLeverageChange,
  onQuantityUnitChange
}: Props) {
  const boundedMaximum = Math.max(1, Math.min(100, Math.floor(maxLeverage)))
  const controlsDisabled = disabled || pending
  const busy = pending || pendingSetting !== null
  const fieldDisabled = (field: TradingSettingField) => controlsDisabled || pendingSetting === field

  const handleLeverageChange = (event: ChangeEvent<HTMLInputElement>) => {
    const value = Number(event.target.value)
    if (Number.isFinite(value)) onLeverageChange(clampLeverage(value, boundedMaximum))
  }

  return (
    <section className={styles.settingsBar} aria-label="Perpetual trading settings" aria-busy={busy}>
      <label className={styles.settingField}>
        <span>Position mode</span>
        <select
          value={positionMode}
          disabled={fieldDisabled('positionMode')}
          onChange={(event) => onPositionModeChange(event.target.value as PositionMode)}
        >
          <option value="ONE_WAY">One-way</option>
          <option value="HEDGE">Hedge</option>
        </select>
      </label>

      <label className={styles.settingField}>
        <span>Margin</span>
        <select
          value={marginMode}
          disabled={fieldDisabled('marginMode')}
          onChange={(event) => onMarginModeChange(event.target.value as MarginMode)}
        >
          <option value="CROSS">Cross</option>
          <option value="ISOLATED">Isolated</option>
        </select>
      </label>

      <label className={styles.settingField}>
        <span>Leverage</span>
        <span className={styles.leverageInput}>
          <input
            type="number"
            inputMode="numeric"
            min={1}
            max={boundedMaximum}
            step={1}
            value={leverage}
            disabled={fieldDisabled('leverage')}
            onChange={handleLeverageChange}
          />
          <span aria-hidden="true">×</span>
        </span>
      </label>

      <label className={styles.settingField}>
        <span>Quantity unit</span>
        <select
          value={quantityUnit}
          disabled={fieldDisabled('quantityUnit')}
          onChange={(event) => onQuantityUnitChange(event.target.value as QuantityUnit)}
        >
          {quantityUnitOptions.map((unit) => (
            <option key={unit} value={unit}>
              {unit}
            </option>
          ))}
        </select>
      </label>

      {busy ? <span className={styles.controlStatus}>Saving…</span> : null}
      {error ? (
        <span className={styles.controlError} role="alert">
          {error}
        </span>
      ) : null}
    </section>
  )
}
