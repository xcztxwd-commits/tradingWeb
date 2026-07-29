import type { AccountTransferResponse, FundingSettlement, Trade } from '@fx-platform/shared-types'
import type {
  AccountSummary,
  AssetLedgerEntry,
  LedgerEntry,
  OrderPayload,
  OrderResponse,
  PositionResponse,
  WalletBalance
} from '../models/index.ts'

export type AccountSessionData = {
  account: AccountSummary
  orders: OrderResponse[]
  trades: Trade[]
  positions: PositionResponse[]
  positionHistory: PositionResponse[]
  fundingSettlements: FundingSettlement[]
  transfers: AccountTransferResponse[]
  ledgerEntries: LedgerEntry[]
  assetLedgerEntries: AssetLedgerEntry[]
  walletBalances: WalletBalance[]
}

export type TradingBalances = Record<string, number>

export function createEmptyAccountSessionData(account: AccountSummary): AccountSessionData {
  return {
    account,
    orders: [],
    trades: [],
    positions: [],
    positionHistory: [],
    fundingSettlements: [],
    transfers: [],
    ledgerEntries: [],
    assetLedgerEntries: [],
    walletBalances: []
  }
}

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
      if (asset) balances[asset] = amountToNumber(wallet.available)
      return balances
    }, {})
  }

  const balances: TradingBalances = {}
  const quoteAsset = parseQuoteAsset(symbol)
  if (account) {
    const currency = account.baseCurrency.trim().toUpperCase()
    const freeMargin = amountToNumber(account.freeMargin)
    if (currency) balances[currency] = freeMargin
    if (isStableCurrencyAlias(currency, quoteAsset)) balances[quoteAsset] = freeMargin
  }
  return balances
}

function parseQuoteAsset(symbol: string) {
  const platformSymbol = symbol.trim().toUpperCase()
  const instrumentSymbol = platformSymbol.endsWith('-PERP')
    ? platformSymbol.slice(0, -'-PERP'.length)
    : platformSymbol
  const normalized = instrumentSymbol.replace(/[-_/]/g, '')
  for (const quote of ['USDT', 'USD', 'JPY']) {
    if (normalized.endsWith(quote)) return quote
  }
  return normalized.slice(3) || 'USDT'
}

function amountToNumber(value: string | number | null | undefined) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function isStableCurrencyAlias(left: string, right: string) {
  return left === right || (left === 'USD' && right === 'USDT') || (left === 'USDT' && right === 'USD')
}
