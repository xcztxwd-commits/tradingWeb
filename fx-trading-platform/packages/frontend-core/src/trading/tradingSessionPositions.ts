import type { Amount, PositionResponse, Quote } from '../models/index.ts'

const FOREX_CONTRACT_SIZE = 100000

export function repriceOpenPositionsForQuote(positions: PositionResponse[], quote: Quote): PositionResponse[] {
  const quoteSymbol = normalizeSymbol(quote.symbol)
  return positions.map((position) => {
    if (position.status.toUpperCase() !== 'OPEN') return position
    if (normalizeSymbol(position.symbol) !== quoteSymbol) return position

    return repriceOpenPosition(position, quote)
  })
}

function repriceOpenPosition(position: PositionResponse, quote: Quote): PositionResponse {
  const closePrice = closingPrice(position.side, quote)
  if (closePrice === null) return position

  const openPrice = toNumber(position.openPrice)
  const lots = toNumber(position.lots)
  if (openPrice === null || lots === null) return position

  const pnl = floatingPnl(position, lots, openPrice, closePrice)
  const markPrice = toNumber(quote.mid) ?? closePrice

  return {
    ...position,
    currentPrice: formatAmount(closePrice),
    markPrice: formatAmount(markPrice),
    floatingPnl: formatAmount(pnl),
    floatingPnlRatio: floatingPnlRatio(pnl, position.marginHeld)
  }
}

function closingPrice(side: string, quote: Quote) {
  const normalizedSide = side.toUpperCase()
  if (normalizedSide === 'BUY') return toNumber(quote.bid)
  if (normalizedSide === 'SELL') return toNumber(quote.ask)
  return null
}

function floatingPnl(position: PositionResponse, lots: number, openPrice: number, currentPrice: number) {
  const diff = position.side.toUpperCase() === 'BUY'
    ? currentPrice - openPrice
    : openPrice - currentPrice
  const instrumentType = position.instrumentType?.toUpperCase()
  return diff * lots * (instrumentType === 'SWAP' ? 1 : FOREX_CONTRACT_SIZE)
}

function floatingPnlRatio(floatingPnl: number, marginHeld: Amount) {
  const margin = toNumber(marginHeld)
  if (margin === null || margin <= 0) return null
  return formatAmount(floatingPnl / margin)
}

function formatAmount(value: number) {
  return value.toFixed(8)
}

function toNumber(value: Amount | null | undefined) {
  if (value === null || value === undefined || value === '') return null
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : null
}

function normalizeSymbol(symbol: string) {
  return symbol.toUpperCase().replace(/[^A-Z0-9]/g, '')
}
