import { ChevronDown, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import type { TradeSide } from '../types/order'

const leverageOptions = [5, 10, 20, 30, 50, 75, 100]

type ToggleProps = {
  open: boolean
  onToggle: () => void
}

export function TradePanelLeverageToggle({ open, onToggle }: ToggleProps) {
  const { t } = useTranslation()

  return (
    <div className="trade-panel__header-actions">
      <button
        type="button"
        className={`trade-panel__leverage-toggle ${open ? 'trade-panel__leverage-toggle--active' : ''}`}
        aria-expanded={open}
        onClick={onToggle}
      >
        <span>{t('trading.leverage')}</span>
        <span className="trade-panel__switch" aria-hidden="true" />
      </button>
    </div>
  )
}

type ControlsProps = {
  leverage: number
  open: boolean
  onClose: () => void
  onOpen: () => void
  onUpdate: (value: number) => void
}

export function TradePanelLeverageControls({ leverage, open, onClose, onOpen, onUpdate }: ControlsProps) {
  const { t } = useTranslation()

  const handleLeverageInput = (value: string) => {
    const nextValue = Number(value)
    if (Number.isFinite(nextValue)) onUpdate(nextValue)
  }

  return (
    <>
      {open ? (
        <div className="trade-panel__leverage-layer" role="presentation">
          <button type="button" className="trade-panel__leverage-backdrop" aria-label={t('trading.closeLeverageDialog')} onClick={onClose} />
          <section className="trade-panel__leverage-popover" role="dialog" aria-modal="true" aria-label={t('trading.adjustLeverage')}>
            <div className="trade-panel__leverage-popover-head">
              <strong>{t('trading.adjustLeverage')}</strong>
              <button type="button" aria-label={t('trading.closeLeverageDialog')} onClick={onClose}>
                <X size={20} aria-hidden="true" />
              </button>
            </div>
            <div className="trade-panel__leverage-body">
              <label className="trade-panel__field">
                <span className="trade-panel__field-label">{t('trading.leverageValue')}</span>
                <span className="trade-panel__control">
                  <input
                    aria-label={t('trading.leverageValue')}
                    inputMode="decimal"
                    value={`${leverage}.00`}
                    onChange={(event) => handleLeverageInput(event.target.value)}
                  />
                  <span className="trade-panel__unit">x</span>
                </span>
              </label>
              <div className="trade-panel__leverage-options" aria-label={t('trading.leverageOptions')}>
                {leverageOptions.map((option) => (
                  <button
                    key={option}
                    type="button"
                    className={option === leverage ? 'trade-panel__leverage-option--active' : ''}
                    onClick={() => onUpdate(option)}
                  >
                    {option === 5 ? '< 5x' : `${option}x`}
                  </button>
                ))}
              </div>
              <div className="trade-panel__leverage-stats">
                <span>
                  {t('trading.leverageMaxOpenAfterChange')} <strong>1.09 {t('trading.contractsUnit')}</strong>
                </span>
                <span>
                  {t('trading.requiredMargin')} <strong>0 USDT</strong>
                </span>
              </div>
              <p className="trade-panel__leverage-hint">{t('trading.highLeverageHint')}</p>
            </div>
            <footer className="trade-panel__leverage-actions">
              <button type="button" className="trade-panel__leverage-cancel" onClick={onClose}>
                {t('common.cancel')}
              </button>
              <button type="button" className="trade-panel__leverage-confirm" onClick={onClose}>
                {t('common.confirm')}
              </button>
            </footer>
          </section>
        </div>
      ) : null}

      <div className="trade-panel__leverage-row" aria-label={t('trading.leverageRow')}>
        <LeverageCell side="buy" leverage={leverage} onOpen={onOpen} />
        <LeverageCell side="sell" leverage={leverage} onOpen={onOpen} />
      </div>
    </>
  )
}

function LeverageCell({ side, leverage, onOpen }: { side: TradeSide; leverage: number; onOpen: () => void }) {
  const { t } = useTranslation()

  return (
    <div className={`trade-panel__leverage-cell trade-panel__leverage-cell--${side}`}>
      <button type="button" onClick={onOpen}>
        {t('trading.crossMargin')}
        <ChevronDown size={13} aria-hidden="true" />
      </button>
      <button type="button" onClick={onOpen}>
        {leverage}x
        <ChevronDown size={13} aria-hidden="true" />
      </button>
    </div>
  )
}
