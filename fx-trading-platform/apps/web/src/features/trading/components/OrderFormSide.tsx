import { useTranslation } from 'react-i18next'

import { AmountInput } from './AmountInput'
import { OrderSubmitButton } from './OrderSubmitButton'
import { PercentSlider } from './PercentSlider'
import { PerpetualOrderOptions } from './PerpetualOrderOptions'
import { PriceInput } from './PriceInput'
import { getRequiredMargin, isMarginQuantityMarket, usesQuoteBudgetMarketBuy } from '../hooks/useTradeForm'
import { formatDecimal } from '../utils/format'
import { parseSymbolAssets } from '../utils/symbols'
import type { OrderValidationResult, TradeBalances, TradeField, TradeFormState, TradeMarket } from '../types/order'

type Props = {
  form: TradeFormState
  market: TradeMarket
  balances: TradeBalances
  minOrderAmount: number
  pricePrecision: number
  quantityPrecision: number
  validation: OrderValidationResult
  showErrors: boolean
  canTrade: boolean
  loginRequired?: boolean
  submitting: boolean
  onFieldChange: (field: TradeField, value: string | number | boolean) => void
  onPriceFocusChange: (focused: boolean) => void
  onPercentChange: (percent: number) => void
  onBestPrice: () => void
  positionMode?: 'ONE_WAY' | 'HEDGE'
  onProtectionsChange: (protections: TradeFormState['attachedProtections']) => void
  onSubmit: () => void
}

const minMarketQuoteAmount = 5

export function OrderFormSide({
  form,
  market,
  balances,
  minOrderAmount,
  pricePrecision,
  quantityPrecision,
  validation,
  showErrors,
  canTrade,
  loginRequired = false,
  submitting,
  onFieldChange,
  onPriceFocusChange,
  onPercentChange,
  onBestPrice,
  positionMode = 'ONE_WAY',
  onProtectionsChange,
  onSubmit
}: Props) {
  const { t } = useTranslation()
  const sideLabel = form.side === 'buy' ? t('common.buy') : t('common.sell')
  const { baseAsset, quoteAsset } = parseSymbolAssets(form.symbol)
  const quoteBalance = balances[quoteAsset] ?? 0
  const baseBalance = balances[baseAsset] ?? 0
  const price = Number(form.price) > 0 ? Number(form.price) : market.lastPrice
  const marginQuantityMarket = isMarginQuantityMarket(market)
  const unitSize = getMarketUnitSize(market)
  const leverage = marginQuantityMarket ? getMarketLeverage(market) : 1
  const maxBuyAmount = price > 0 ? quoteBalance * leverage / (price * unitSize) : 0
  const maxSellAmount = marginQuantityMarket && price > 0 ? quoteBalance * leverage / (price * unitSize) : baseBalance
  const maxSellValue = price > 0 ? baseBalance * price : 0
  const marketBuyAmount = usesQuoteBudgetMarketBuy(form, market)
  const quoteQuantity = marginQuantityMarket && form.quantityUnit === 'QUOTE'
  const amountField: TradeField = marketBuyAmount || quoteQuantity ? 'total' : 'amount'
  const amountValue = marketBuyAmount || quoteQuantity ? form.total : form.amount
  const amountUnit = marketBuyAmount
    ? t('trading.minWithAsset', { amount: minMarketQuoteAmount, asset: quoteAsset })
    : quoteQuantity ? quoteAsset
      : marginQuantityMarket && form.quantityUnit === 'CONTRACTS' ? t('trading.contractsUnit') : baseAsset
  const requiredQuoteAmount = getRequiredMargin(form, market)
  const quoteShortfall = Math.max(0, requiredQuoteAmount - quoteBalance)
  const baseShortfall = Math.max(0, Number(form.amount) - baseBalance)

  const getError = (...keys: string[]) => {
    if (!showErrors) return undefined
    return keys.map((key) => validation.fieldErrors[key as keyof typeof validation.fieldErrors]).find(Boolean)
  }
  const marketStaleError = validation.errors.includes('marketStale') ? t('validation.marketStale') : undefined
  const quoteBalanceError = getError('quoteBalance')
  const baseBalanceError = getError('baseBalance')

  return (
    <section
      className={`trade-panel__side trade-panel__side--${form.side} trade-panel__side--${form.orderType} trade-panel__side--strategy-${form.strategyType}`}
      aria-label={t('trading.sideForm', { side: sideLabel })}
      data-price-precision={pricePrecision}
      data-quantity-precision={quantityPrecision}
      data-min-amount={minOrderAmount}
    >
      {form.strategyType === 'trigger' || form.strategyType === 'oco' ? (
        <TriggerPriceField
          value={form.triggerPrice}
          unit={quoteAsset}
          error={getError('triggerPrice')}
          onChange={(value) => onFieldChange('triggerPrice', value)}
        />
      ) : null}

      {form.orderType === 'limit' && form.strategyType !== 'trigger' ? (
        <PriceInput
          ariaLabel={t('trading.sidePrice', { side: sideLabel })}
          value={form.price}
          unit={market.quoteAsset}
          bestPriceDisabled={market.bestAsk <= 0 || market.bestBid <= 0 || form.tpSlEnabled}
          error={getError('price')}
          onBestPrice={onBestPrice}
          onChange={(value) => onFieldChange('price', value)}
          onFocusChange={onPriceFocusChange}
        />
      ) : (
        <>
          <StaticOrderField label={t('common.price')} value={t('trading.marketPrice')} disabled />
          {marketStaleError ? <span className="trade-panel__error">{marketStaleError}</span> : null}
        </>
      )}

      <AmountInput
        ariaLabel={t('trading.sideQuantity', { side: sideLabel })}
        label={marketBuyAmount || quoteQuantity ? t('trading.orderAmount') : t('common.quantity')}
        value={amountValue}
        unit={amountUnit}
        unitDropdown={form.orderType === 'market'}
        showStepper={form.orderType === 'limit'}
        error={getError('amount', 'minAmount', 'baseBalance', 'quoteBalance', 'minNotional')}
        onChange={(value) => onFieldChange(amountField, value)}
      />

      <PercentSlider value={form.percent} onChange={onPercentChange} />

      {marginQuantityMarket ? (
        <PerpetualOrderOptions
          form={form}
          positionMode={positionMode}
          disabled={!canTrade || submitting}
          error={getError('attachedProtections')}
          onFieldChange={onFieldChange}
          onProtectionsChange={onProtectionsChange}
        />
      ) : null}

      {form.orderType === 'market' ? <SlippageTolerance expanded={false} /> : null}

      <BalanceSummary
        canTrade={canTrade}
        side={form.side}
        quoteAsset={quoteAsset}
        baseAsset={baseAsset}
        quoteBalance={quoteBalance}
        baseBalance={baseBalance}
        marginQuantityMarket={marginQuantityMarket}
        maxBuyAmount={maxBuyAmount}
        maxSellAmount={maxSellAmount}
        maxSellValue={maxSellValue}
        requiredQuoteAmount={requiredQuoteAmount}
        quoteShortfall={quoteShortfall}
        baseShortfall={baseShortfall}
        quoteBalanceError={quoteBalanceError}
        baseBalanceError={baseBalanceError}
      />

      <OrderSubmitButton
        side={form.side}
        baseAsset={baseAsset}
        canTrade={canTrade}
        disabledReason={marketStaleError}
        loginRequired={loginRequired}
        submitting={submitting}
        onClick={onSubmit}
      />
    </section>
  )
}

function TriggerPriceField({
  value,
  unit,
  error,
  onChange
}: {
  value: string
  unit: string
  error?: string
  onChange: (value: string) => void
}) {
  const { t } = useTranslation()

  return (
    <label className={`trade-panel__field trade-panel__strategy-trigger ${error ? 'trade-panel__field--invalid' : ''}`}>
      <span className="trade-panel__control trade-panel__control--with-stepper">
        <span className="trade-panel__field-label">{t('trading.triggerPrice')}</span>
        <input
          aria-label={t('trading.triggerPrice')}
          inputMode="decimal"
          placeholder=""
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
        <span className="trade-panel__unit">{unit}</span>
        <span className="trade-panel__stepper" aria-hidden="true"><span /><span /></span>
      </span>
      {error ? <span className="trade-panel__error">{error}</span> : null}
    </label>
  )
}

function StaticOrderField({ label, value, disabled = false }: { label: string; value: string; disabled?: boolean }) {
  return (
    <label className="trade-panel__field">
      <span className={`trade-panel__control trade-panel__control--static ${disabled ? 'trade-panel__control--disabled' : ''}`}>
        <span className="trade-panel__field-label">{label}</span>
        <input aria-label={label} value={value} disabled readOnly />
      </span>
    </label>
  )
}

function SlippageTolerance({ expanded }: { expanded: boolean }) {
  const { t } = useTranslation()

  if (!expanded) {
    return (
      <label className="trade-panel__check trade-panel__slippage-check">
        <input type="checkbox" readOnly />
        <span>{t('trading.slippageTolerance')}</span>
      </label>
    )
  }

  return (
    <section className="trade-panel__slippage">
      <span className="trade-panel__mini-title">{t('trading.slippageTolerance')}</span>
      <label className="trade-panel__field">
        <span className="trade-panel__control trade-panel__control--compact">
          <span className="trade-panel__field-label">{t('trading.slippage')}</span>
          <input aria-label={t('trading.slippage')} inputMode="decimal" placeholder="" />
          <span className="trade-panel__unit trade-panel__unit--dropdown">{t('trading.minimumPercent', { value: '0.1' })}</span>
        </span>
      </label>
    </section>
  )
}

function BalanceSummary({
  canTrade,
  side,
  quoteAsset,
  baseAsset,
  quoteBalance,
  baseBalance,
  marginQuantityMarket,
  maxBuyAmount,
  maxSellAmount,
  maxSellValue,
  requiredQuoteAmount,
  quoteShortfall,
  baseShortfall,
  quoteBalanceError,
  baseBalanceError
}: {
  canTrade: boolean
  side: TradeFormState['side']
  quoteAsset: string
  baseAsset: string
  quoteBalance: number
  baseBalance: number
  marginQuantityMarket: boolean
  maxBuyAmount: number
  maxSellAmount: number
  maxSellValue: number
  requiredQuoteAmount: number
  quoteShortfall: number
  baseShortfall: number
  quoteBalanceError?: string
  baseBalanceError?: string
}) {
  const { t } = useTranslation()
  const availableAsset = side === 'buy' || marginQuantityMarket ? quoteAsset : baseAsset
  const maxAsset = side === 'buy' || marginQuantityMarket ? baseAsset : quoteAsset
  const availableValue = side === 'buy' || marginQuantityMarket ? quoteBalance : baseBalance
  const maxValue = side === 'buy' ? maxBuyAmount : marginQuantityMarket ? maxSellAmount : maxSellValue

  return (
    <div className="trade-panel__balance" aria-label={t('trading.sidePreview', { side })}>
      <span>
        <span>{t('trading.available')}</span>
        <strong>{canTrade ? formatBalance(availableValue) : '-'} {availableAsset}</strong>
      </span>
      <span>
        <span>{side === 'buy' ? t('trading.maxBuy') : t('trading.maxSell')}</span>
        <strong>{canTrade ? formatDecimal(maxValue) || '--' : '--'} {maxAsset}</strong>
      </span>
      {quoteBalanceError ? (
        <span className="trade-panel__balance-alert">
          <span>{marginQuantityMarket ? t('trading.marginRequirement') : t('trading.balanceShortfall')}</span>
          <strong>
            {marginQuantityMarket
              ? t('trading.marginRequirementValue', {
                required: formatBalance(requiredQuoteAmount),
                available: formatBalance(quoteBalance),
                asset: quoteAsset
              })
              : t('trading.balanceShortfallValue', { amount: formatBalance(quoteShortfall), asset: quoteAsset })}
          </strong>
        </span>
      ) : null}
      {baseBalanceError ? (
        <span className="trade-panel__balance-alert">
          <span>{t('trading.balanceShortfall')}</span>
          <strong>{t('trading.balanceShortfallValue', { amount: formatBalance(baseShortfall), asset: baseAsset })}</strong>
        </span>
      ) : null}
    </div>
  )
}

function formatBalance(value: number) {
  return value.toLocaleString(undefined, { maximumFractionDigits: 8 })
}

function getMarketUnitSize(market: TradeMarket) {
  return market.unitSize && market.unitSize > 0 ? market.unitSize : 1
}

function getMarketLeverage(market: TradeMarket) {
  return market.leverage && market.leverage > 0 ? market.leverage : 1
}
