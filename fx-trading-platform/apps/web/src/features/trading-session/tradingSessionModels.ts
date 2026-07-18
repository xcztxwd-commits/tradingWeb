import type { AccountSummary, OrderPayload, OrderResponse, PositionResponse, WalletBalance } from '@fx-platform/frontend-core'
import { parseSymbolAssets } from '../trading/utils/symbols.ts'

export type TradingBalances = Record<string, number>

export function createLocalPreviewOrder(payload: OrderPayload, createdAt = new Date().toISOString()): OrderResponse {
  return {
    id: payload.clientOrderId,
    symbol: payload.symbol,
    side: payload.side,
    orderType: payload.orderType,
    status: 'LOCAL_PREVIEW',
    lots: payload.quantity,
    executionPrice: null,
    createdAt
  }
}

export function deriveTradingBalances(
  product: 'spot' | 'perpetual',
  account: AccountSummary | undefined,
  _positions: PositionResponse[],
  symbol: string,
  walletBalances: WalletBalance[] = []
): TradingBalances {
  if (product === 'spot') {
    return walletBalances.reduce<TradingBalances>((balances, wallet) => {
      if (wallet.walletType.trim().toUpperCase() !== 'SPOT') return balances
      const asset = wallet.asset.trim().toUpperCase()
      if (asset) {
        balances[asset] = amountToNumber(wallet.available)
      }
      return balances
    }, {})
  }

  const balances: TradingBalances = {}
  const { quoteAsset } = parseSymbolAssets(symbol)

  if (account) {
    const currency = account.baseCurrency.trim().toUpperCase()
    const freeMargin = amountToNumber(account.freeMargin)
    if (currency) balances[currency] = freeMargin

    if (isStableCurrencyAlias(currency, quoteAsset)) {
      balances[quoteAsset] = freeMargin
    }
  }

  return balances
}

function amountToNumber(value: string | number | null | undefined) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function isStableCurrencyAlias(left: string, right: string) {
  return left === right || (left === 'USD' && right === 'USDT') || (left === 'USDT' && right === 'USD')
}
