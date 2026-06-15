import { useTranslation } from 'react-i18next'

import { AdvancedOrderPanel } from './AdvancedOrderPanel'
import { AmountInput } from './AmountInput'
import { OrderSubmitButton } from './OrderSubmitButton'
import { PercentSlider } from './PercentSlider'
import { PriceInput } from './PriceInput'
import { TpSlPanel } from './TpSlPanel'
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
  leverage: number
  submitting: boolean
  onFieldChange: (field: TradeField, value: string | number | boolean) => void
  onPriceFocusChange: (focused: boolean) => void
  onPercentChange: (percent: number) => void
  onBestPrice: () => void
  onSubmit: () => void
}

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
  leverage,
  submitting,
  onFieldChange,
  onPriceFocusChange,
  onPercentChange,
  onBestPrice,
  onSubmit
}: Props) {
  const { t } = useTranslation()
  const sideLabel = form.side === 'buy' ? t('trading.openLong') : t('trading.openShort')
  const { baseAsset, quoteAsset } = parseSymbolAssets(form.symbol)
  const quoteBalance = balances[quoteAsset] ?? 0
  const baseBalance = balances[baseAsset] ?? 0
  const price = Number(form.price) > 0 ? Number(form.price) : market.lastPrice
  const maxBuyAmount = price > 0 ? quoteBalance / price : 0
  const amount = Number(form.amount) > 0 ? Number(form.amount) : 0
  const totalValue = Number(form.total) > 0 ? Number(form.total) : price * amount
  const estimatedCost = leverage > 0 ? totalValue / leverage : totalValue
  const forcePrice = form.side === 'buy' ? market.bestBid : market.bestAsk
  const forcePriceLabel = form.side === 'buy' ? t('trading.highestBid') : t('trading.lowestAsk')
  const maxOpenAmount = form.side === 'buy' ? maxBuyAmount : baseBalance
  const contractValue = getContractValue(baseAsset, minOrderAmount, quantityPrecision)

  const getError = (...keys: string[]) => {
    if (!showErrors) return undefined
    return keys.map((key) => validation.fieldErrors[key as keyof typeof validation.fieldErrors]).find(Boolean)
  }

  return (
    <section
      className={`trade-panel__side trade-panel__side--${form.side} trade-panel__side--${form.orderType}`}
      aria-label={t('trading.sideForm', { side: sideLabel })}
      data-price-precision={pricePrecision}
    >
      {form.orderType === 'limit' ? (
        <PriceInput
          ariaLabel={t('trading.sidePrice', { side: sideLabel })}
          value={form.price}
          unit={market.quoteAsset}
          bestPriceDisabled={market.bestAsk <= 0 || market.bestBid <= 0}
          error={getError('price')}
          onBestPrice={onBestPrice}
          onChange={(value) => onFieldChange('price', value)}
          onFocusChange={onPriceFocusChange}
        />
      ) : null}

      <AmountInput
        ariaLabel={t('trading.sideQuantity', { side: sideLabel })}
        label={t('common.quantity')}
        value={form.amount}
        unit={t('trading.contractsUnit')}
        caption={t('trading.singleContractValue', { value: contractValue, asset: baseAsset })}
        error={getError('amount', 'minAmount', 'baseBalance', 'quoteBalance', 'minNotional')}
        onChange={(value) => onFieldChange('amount', value)}
      />

      <PercentSlider value={form.percent} onChange={onPercentChange} />

      <div className="trade-panel__balance">
        <div>
          <span>
            {t('trading.availableBalance')} <strong>{formatBalance(quoteBalance)}</strong> {quoteAsset}
          </span>
          <span>
            {form.side === 'buy' ? t('trading.maxOpenLong') : t('trading.maxOpenShort')}{' '}
            <strong>{formatDecimal(maxOpenAmount) || '--'}</strong> {t('trading.contractsUnit')}
          </span>
        </div>
        <button type="button" aria-label={t('trading.addFunds')} className="trade-panel__balance-add">
          +
        </button>
      </div>

      <TpSlPanel form={form} showErrors={showErrors} fieldErrors={validation.fieldErrors} onFieldChange={onFieldChange} />
      <AdvancedOrderPanel
        form={form}
        showErrors={showErrors}
        fieldErrors={validation.fieldErrors}
        onFieldChange={(field, value) => onFieldChange(field, value)}
      />

      <OrderSubmitButton
        side={form.side}
        baseAsset={baseAsset}
        canTrade={canTrade}
        loginRequired={loginRequired}
        submitting={submitting}
        onClick={onSubmit}
      />

      <div className="trade-panel__order-metrics" aria-label={t('trading.sidePreview', { side: sideLabel })}>
        <span>
          {t('trading.estimatedCost')} <strong>{formatPreviewValue(estimatedCost, quoteAsset)}</strong>
        </span>
        <span>
          {forcePriceLabel} <strong>{formatFiatForcePrice(forcePrice)}</strong>
        </span>
        <span>
          {t('trading.estimatedLiquidationPrice')} <strong>--</strong>
        </span>
      </div>
    </section>
  )
}

function formatBalance(value: number) {
  return value.toLocaleString(undefined, { maximumFractionDigits: 8 })
}

function formatPreviewValue(value: number, unit: string) {
  if (!Number.isFinite(value) || value <= 0) return `-- ${unit}`
  return `${formatDecimal(value) || value.toFixed(2)} ${unit}`
}

function formatMinAmount(value: number, decimals: number) {
  if (!Number.isFinite(value) || value <= 0) return '--'
  return formatDecimal(value, Math.max(decimals, 0)) || String(value)
}

function getContractValue(baseAsset: string, minOrderAmount: number, quantityPrecision: number) {
  if (baseAsset === 'BTC') return '0.01'
  return formatMinAmount(minOrderAmount, quantityPrecision)
}

function formatFiatForcePrice(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '--'
  return `¥${value.toLocaleString(undefined, { maximumFractionDigits: 1 })}`
}
