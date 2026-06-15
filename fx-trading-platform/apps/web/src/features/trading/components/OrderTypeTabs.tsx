import { useTranslation } from 'react-i18next'

import { StrategyDropdown } from './StrategyDropdown'
import type { PrimaryOrderType, StrategyType } from '../types/order'

type Props = {
  orderType: PrimaryOrderType
  strategyType: StrategyType
  onOrderTypeChange: (value: PrimaryOrderType) => void
  onStrategyChange: (value: StrategyType) => void
  onUnavailable: (label: string) => void
}

export function OrderTypeTabs({
  orderType,
  strategyType,
  onOrderTypeChange,
  onStrategyChange,
  onUnavailable
}: Props) {
  const { t } = useTranslation()

  return (
    <div className="trade-panel__order-tabs" role="tablist" aria-label={t('trading.orderType')}>
      <button
        type="button"
        className={`trade-panel__order-tab ${orderType === 'limit' ? 'trade-panel__order-tab--active' : ''}`}
        role="tab"
        aria-selected={orderType === 'limit'}
        onClick={() => onOrderTypeChange('limit')}
      >
        {t('trading.limitOrder')}
      </button>
      <button
        type="button"
        className={`trade-panel__order-tab ${orderType === 'market' ? 'trade-panel__order-tab--active' : ''}`}
        role="tab"
        aria-selected={orderType === 'market'}
        onClick={() => onOrderTypeChange('market')}
      >
        {t('trading.marketOrder')}
      </button>
      <StrategyDropdown value={strategyType} onChange={onStrategyChange} onUnavailable={onUnavailable} />
    </div>
  )
}
