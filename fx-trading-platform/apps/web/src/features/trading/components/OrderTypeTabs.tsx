import { Info } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { StrategyDropdown } from './StrategyDropdown'
import type { PrimaryOrderType, StrategyType } from '../types/order'

type Props = {
  orderType: PrimaryOrderType
  strategyType: StrategyType
  onOrderTypeChange: (value: PrimaryOrderType) => void
  onStrategyOrderTypeChange: (value: PrimaryOrderType) => void
}

export function OrderTypeTabs({
  orderType,
  strategyType,
  onOrderTypeChange,
  onStrategyOrderTypeChange
}: Props) {
  const { t } = useTranslation()
  const strategyActive = strategyType === 'tp_sl'

  return (
    <div className="trade-panel__order-tabs" role="tablist" aria-label={t('trading.orderType')}>
      <button
        type="button"
        className={`trade-panel__order-tab ${!strategyActive && orderType === 'limit' ? 'trade-panel__order-tab--active' : ''}`}
        role="tab"
        aria-selected={!strategyActive && orderType === 'limit'}
        onClick={() => onOrderTypeChange('limit')}
      >
        {t('trading.limitOrder')}
      </button>
      <button
        type="button"
        className={`trade-panel__order-tab ${!strategyActive && orderType === 'market' ? 'trade-panel__order-tab--active' : ''}`}
        role="tab"
        aria-selected={!strategyActive && orderType === 'market'}
        onClick={() => onOrderTypeChange('market')}
      >
        {t('trading.marketOrder')}
      </button>
      <StrategyDropdown active={strategyActive} orderType={orderType} onChange={onStrategyOrderTypeChange} />
      <span className="trade-panel__order-info" aria-hidden="true">
        <Info size={14} />
      </span>
    </div>
  )
}
