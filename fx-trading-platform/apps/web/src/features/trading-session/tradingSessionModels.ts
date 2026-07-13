import type { OrderResponse, PositionResponse } from '../../components/tables/types'
import { parseSymbolAssets } from '../trading/utils/symbols.ts'
import type { AccountSummary, OrderPayload, WalletBalance } from '../../types/trading'

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
  account: AccountSummary | undefined,
  positions: PositionResponse[],
  symbol: string,
  walletBalances: WalletBalance[] = []
): TradingBalances {
  if (walletBalances.length > 0) {
    return walletBalances.reduce<TradingBalances>((balances, wallet) => {
      const asset = wallet.asset.trim().toUpperCase()
      if (asset) {
        balances[asset] = amountToNumber(wallet.available)
      }
      return balances
    }, {})
  }

  const balances: TradingBalances = {}
  const { baseAsset, quoteAsset } = parseSymbolAssets(symbol)

  if (account) {
    const currency = account.baseCurrency.toUpperCase()
    const freeMargin = amountToNumber(account.freeMargin)
    balances[currency] = freeMargin

    if (isStableCurrencyAlias(currency, quoteAsset)) {
      balances[quoteAsset] = freeMargin
    }
  }

  const selectedSymbol = normalizeSymbol(symbol)
  const baseLots = positions.reduce((total, position) => {
    if (normalizeSymbol(position.symbol) !== selectedSymbol) return total
    if (position.status.toUpperCase() === 'CLOSED') return total
    if (position.side.toUpperCase() !== 'BUY') return total
    return total + amountToNumber(position.lots)
  }, 0)

  if (baseLots > 0) {
    balances[baseAsset] = (balances[baseAsset] ?? 0) + baseLots
  }

  return balances
}

function normalizeSymbol(symbol: string) {
  return symbol.replace(/[-_/]/g, '').toUpperCase()
}

function amountToNumber(value: string | number | null | undefined) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function isStableCurrencyAlias(left: string, right: string) {
  return left === right || (left === 'USD' && right === 'USDT') || (left === 'USDT' && right === 'USD')
}
