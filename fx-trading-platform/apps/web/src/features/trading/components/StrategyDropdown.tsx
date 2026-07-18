import { Check, ChevronDown } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

import type { PrimaryOrderType } from '@fx-platform/frontend-core'

type Props = {
  active: boolean
  orderType: PrimaryOrderType
  onChange: (value: PrimaryOrderType) => void
}

const strategyChoices: Array<{ id: 'limit_tp_sl' | 'market_tp_sl'; orderType: PrimaryOrderType; labelKey: string }> = [
  { id: 'limit_tp_sl', orderType: 'limit', labelKey: 'trading.limitTpSl' },
  { id: 'market_tp_sl', orderType: 'market', labelKey: 'trading.marketTpSl' }
]

export function StrategyDropdown({ active, orderType, onChange }: Props) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const selected = active
    ? strategyChoices.find((option) => option.orderType === orderType) ?? strategyChoices[0]
    : strategyChoices[0]

  useEffect(() => {
    setOpen(false)
  }, [active, orderType])

  return (
    <div className="trade-panel__strategy">
      <button
        type="button"
        className={`trade-panel__order-tab ${active ? 'trade-panel__order-tab--active' : ''}`}
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        {t(selected.labelKey)}
        <ChevronDown size={14} aria-hidden="true" />
      </button>

      {open ? (
        <div className="trade-panel__strategy-menu" role="menu">
          {strategyChoices.map((option) => {
            const optionActive = active && option.orderType === orderType
            return (
              <button
                key={option.id}
                type="button"
                className={`trade-panel__strategy-item ${optionActive ? 'trade-panel__strategy-item--active' : ''}`}
                role="menuitem"
                onClick={() => {
                  setOpen(false)
                  onChange(option.orderType)
                }}
              >
                <span>{t(option.labelKey)}</span>
                {optionActive ? <Check size={14} aria-hidden="true" /> : null}
              </button>
            )
          })}
        </div>
      ) : null}
    </div>
  )
}
