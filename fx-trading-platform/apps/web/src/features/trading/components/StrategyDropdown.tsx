import { ChevronDown } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'

import { strategyOptions } from '../types/order'
import type { StrategyType } from '../types/order'

type Props = {
  value: StrategyType
  onChange: (value: StrategyType) => void
  onUnavailable: (label: string) => void
}

export function StrategyDropdown({ value, onChange, onUnavailable }: Props) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const selected = strategyOptions.find((option) => option.value === value)

  return (
    <div className="trade-panel__strategy">
      <button
        type="button"
        className={`trade-panel__order-tab ${value !== 'none' ? 'trade-panel__order-tab--active' : ''}`}
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        {selected ? t(selected.labelKey) : t('trading.strategy.tpSl')}
        <ChevronDown size={14} aria-hidden="true" />
      </button>

      {open ? (
        <div className="trade-panel__strategy-menu" role="menu">
          {strategyOptions.map((option) => (
            <button
              key={option.value}
              type="button"
              className={`trade-panel__strategy-item ${
                option.value === value ? 'trade-panel__strategy-item--active' : ''
              } ${option.available ? '' : 'trade-panel__strategy-item--disabled'}`}
              role="menuitem"
              aria-disabled={!option.available}
              onClick={() => {
                setOpen(false)
                if (!option.available) {
                  onUnavailable(t(option.labelKey))
                  return
                }
                onChange(option.value)
              }}
            >
              {t(option.labelKey)}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}
