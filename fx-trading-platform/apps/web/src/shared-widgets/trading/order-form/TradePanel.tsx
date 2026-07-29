import { useTranslation } from 'react-i18next'

import { OrderConfirmationDialog } from './OrderConfirmationDialog'
import { OrderFormSide } from './OrderFormSide'
import { OrderTypeTabs } from './OrderTypeTabs'
import { TradeTabs } from './TradeTabs'
import type { TradePanelControllerModel } from './useTradePanelController'
import styles from './TradePanel.module.css'

export function TradePanel({
  model,
  compact = false
}: {
  model: TradePanelControllerModel
  compact?: boolean
}) {
  const { t } = useTranslation()
  const {
    activeOrderType,
    activeStrategyType,
    attempted,
    balances,
    buyForm,
    canTrade,
    confirmation,
    confirmSubmit,
    effectiveMinOrderAmount,
    effectivePricePrecision,
    effectiveQuantityPrecision,
    loginRequired,
    market,
    mobileSide,
    notice,
    requestSubmit,
    sellForm,
    setConfirmation,
    setMobileSide,
    skipConfirm,
    submittingSide,
    updateBothOrderTypes,
    updateBothStrategyTypes,
    updateSkipConfirm
  } = model

  return (
    <section
      className={`${styles['trade-panel']} ${compact ? styles['trade-panel--compact'] : ''} ${styles[`trade-panel--mobile-${mobileSide}`]}`}
      aria-label={t('trading.panelForSymbol', { symbol: market.symbol })}
    >
      <header className={styles['trade-panel__header']}>
        <TradeTabs productType={market.productType} />
      </header>

      <OrderTypeTabs
        orderType={activeOrderType}
        strategyType={activeStrategyType}
        allowOco={market.productType === 'CRYPTO_SPOT'}
        onOrderTypeChange={updateBothOrderTypes}
        onStrategyTypeChange={updateBothStrategyTypes}
      />

      <div className={styles['trade-panel__mobile-sides']} role="tablist" aria-label={t('trading.sideTabs')}>
        <button
          type="button"
          className={mobileSide === 'buy' ? styles['trade-panel__mobile-side--active'] : ''}
          onClick={() => setMobileSide('buy')}
        >
          {t('common.buy')}
        </button>
        <button
          type="button"
          className={mobileSide === 'sell' ? styles['trade-panel__mobile-side--active'] : ''}
          onClick={() => setMobileSide('sell')}
        >
          {t('common.sell')}
        </button>
      </div>

      <div className={`${styles['trade-panel__forms']} ${styles['trade-panel__forms--dual']}`}>
        <OrderFormSide
          form={buyForm.form}
          market={market}
          balances={balances}
          minOrderAmount={effectiveMinOrderAmount}
          pricePrecision={effectivePricePrecision}
          quantityPrecision={effectiveQuantityPrecision}
          validation={buyForm.validation}
          showErrors={attempted.buy}
          canTrade={canTrade}
          loginRequired={loginRequired}
          submitting={submittingSide === 'buy'}
          onFieldChange={buyForm.updateField}
          onPriceFocusChange={buyForm.setPriceFocused}
          onPercentChange={buyForm.setPercent}
          onBestPrice={buyForm.fillBestPrice}
          positionMode={model.positionMode}
          onProtectionsChange={buyForm.setAttachedProtections}
          onSubmit={() => requestSubmit(buyForm.form, buyForm.validation, buyForm.reset)}
        />
        <OrderFormSide
          form={sellForm.form}
          market={market}
          balances={balances}
          minOrderAmount={effectiveMinOrderAmount}
          pricePrecision={effectivePricePrecision}
          quantityPrecision={effectiveQuantityPrecision}
          validation={sellForm.validation}
          showErrors={attempted.sell}
          canTrade={canTrade}
          loginRequired={loginRequired}
          submitting={submittingSide === 'sell'}
          onFieldChange={sellForm.updateField}
          onPriceFocusChange={sellForm.setPriceFocused}
          onPercentChange={sellForm.setPercent}
          onBestPrice={sellForm.fillBestPrice}
          positionMode={model.positionMode}
          onProtectionsChange={sellForm.setAttachedProtections}
          onSubmit={() => requestSubmit(sellForm.form, sellForm.validation, sellForm.reset)}
        />
      </div>

      {notice ? (
        <footer className={styles['trade-panel__notice']} role="status">
          <span>{notice}</span>
        </footer>
      ) : null}

      {confirmation ? (
        <OrderConfirmationDialog
          payload={confirmation.payload}
          skipConfirm={skipConfirm}
          submitting={submittingSide === confirmation.form.side}
          onCancel={() => setConfirmation(null)}
          onConfirm={confirmSubmit}
          onSkipConfirmChange={updateSkipConfirm}
        />
      ) : null}
    </section>
  )
}
