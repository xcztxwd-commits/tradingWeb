import { useTranslation } from 'react-i18next'

import type { PrimaryOrderType, StrategyType } from '@fx-platform/frontend-core'

type Props = {
  orderType: PrimaryOrderType
  strategyType: StrategyType
  allowOco: boolean
  onOrderTypeChange: (value: PrimaryOrderType) => void
  onStrategyTypeChange: (value: 'trigger' | 'oco') => void
}

export function OrderTypeTabs({
  orderType,
  strategyType,
  allowOco,
  onOrderTypeChange,
  onStrategyTypeChange
}: Props) {
  const { t } = useTranslation()
  const strategyActive = strategyType === 'trigger' || strategyType === 'oco'

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
      <button
        type="button"
        className={`trade-panel__order-tab ${strategyType === 'trigger' ? 'trade-panel__order-tab--active' : ''}`}
        role="tab"
        aria-selected={strategyType === 'trigger'}
        onClick={() => onStrategyTypeChange('trigger')}
      >
        Stop Market
      </button>
      {allowOco ? (
        <button
          type="button"
          className={`trade-panel__order-tab ${strategyType === 'oco' ? 'trade-panel__order-tab--active' : ''}`}
          role="tab"
          aria-selected={strategyType === 'oco'}
          onClick={() => onStrategyTypeChange('oco')}
        >
          OCO
        </button>
      ) : null}
    </div>
  )
}
