import type { PositionResponse } from '../../../components/tables/types'
import type { Amount } from '../../../types/trading'

export type PositionPnlTone = 'positive' | 'negative' | 'neutral'

export type PositionDisplayRow = {
  id: string
  instrument: string
  leverage: string
  quantity: string
  markPrice: string
  openPrice: string
  notional: string
  liquidationPrice: string
  breakEvenPrice: string
  floatingPnl: string
  floatingPnlTone: PositionPnlTone
  realizedPnl: string
  realizedPnlTone: PositionPnlTone
  maintenanceMargin: string | null
  showMaintenanceMargin: boolean
  maintenanceMarginRate: string
  margin: string
  marginMode: string
  stopLoss: string
  takeProfit: string
  adlLevel: number | null
  status: string
  canClose: boolean
}

type Translate = (key: string, options?: Record<string, unknown>) => string

const defaultTranslate: Translate = (key) => key

export function createPositionDisplayRow(position: PositionResponse, t: Translate = defaultTranslate): PositionDisplayRow {
  const showMaintenanceMargin = !isSpotPosition(position)
  const canClose = position.status.toUpperCase() !== 'CLOSED' && !isSpotPosition(position)

  return {
    id: position.id,
    instrument: formatInstrument(position, t),
    leverage: formatLeverage(position.leverage),
    quantity: `${formatDecimal(position.lots)} ${unitLabel(position.positionUnit, t)}`,
    markPrice: formatDecimal(position.markPrice ?? position.currentPrice),
    openPrice: formatDecimal(position.openPrice),
    notional: formatNotional(position),
    liquidationPrice: formatDecimal(position.liquidationPrice),
    breakEvenPrice: formatDecimal(position.breakEvenPrice ?? position.openPrice),
    floatingPnl: formatFloatingPnl(position),
    floatingPnlTone: pnlTone(position.floatingPnl),
    realizedPnl: formatPnlAmount(position.realizedPnl, position),
    realizedPnlTone: pnlTone(position.realizedPnl),
    maintenanceMargin: showMaintenanceMargin ? formatMaintenanceMargin(position.maintenanceMargin, position) : null,
    showMaintenanceMargin,
    maintenanceMarginRate: formatPercent(position.maintenanceMarginRate),
    margin: `${formatDecimal(position.marginHeld)} ${settlementCurrency(position)}`,
    marginMode: marginModeLabel(position.marginMode, t),
    stopLoss: formatDecimal(position.stopLoss),
    takeProfit: formatDecimal(position.takeProfit),
    adlLevel: typeof position.adlLevel === 'number' ? position.adlLevel : null,
    status: position.status,
    canClose
  }
}

function formatInstrument(position: PositionResponse, t: Translate) {
  const suffix = instrumentTypeLabel(position.instrumentType, t)
  return suffix ? `${position.symbol} ${suffix}` : position.symbol
}

function instrumentTypeLabel(type: string | null | undefined, t: Translate) {
  const normalized = type?.toUpperCase()
  if (normalized === 'SWAP') return t('positions.instrumentTypes.swap')
  if (normalized === 'FUTURES') return t('positions.instrumentTypes.futures')
  if (normalized === 'SPOT') return t('positions.instrumentTypes.spot')
  if (normalized === 'FOREX') return t('positions.instrumentTypes.forex')
  return ''
}

function formatLeverage(leverage?: number | null) {
  return typeof leverage === 'number' && Number.isFinite(leverage) ? `${leverage}x` : '--'
}

function unitLabel(unit: string | null | undefined, t: Translate) {
  const normalized = unit?.toUpperCase()
  if (normalized === 'CONTRACT') return t('positions.units.contract')
  if (normalized === 'LOT') return t('positions.units.lot')
  return unit || ''
}

function formatFloatingPnl(position: PositionResponse) {
  const amount = formatDecimal(position.floatingPnl)
  const ratio = formatPercent(position.floatingPnlRatio)
  const currency = settlementCurrency(position)
  return ratio === '--' ? `${amount} ${currency}` : `${amount} ${currency} (${ratio})`
}

function formatPnlAmount(value: Amount, position: PositionResponse) {
  return `${formatDecimal(value)} ${settlementCurrency(position)}`
}

function formatNotional(position: PositionResponse) {
  const amount = formatDecimal(position.notional)
  if (amount === '--') return amount
  const currency = settlementCurrency(position)
  return currency ? `${amount} ${currency}` : amount
}

function formatMaintenanceMargin(value: Amount | null | undefined, position: PositionResponse) {
  const amount = formatDecimal(value)
  if (amount === '--') return amount
  const currency = settlementCurrency(position)
  return currency ? `${amount} ${currency}` : amount
}

function formatPercent(value?: Amount | null) {
  const numeric = toNumber(value)
  if (numeric === null) return '--'
  return `${numeric >= 0 ? '+' : ''}${formatNumber(numeric * 100, 2)}%`
}

function formatDecimal(value?: Amount | null) {
  const numeric = toNumber(value)
  if (numeric === null) return '--'
  return formatNumber(numeric, inferPrecision(numeric))
}

function formatNumber(value: number, maximumFractionDigits: number) {
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 0,
    maximumFractionDigits
  }).format(value)
}

function inferPrecision(value: number) {
  const abs = Math.abs(value)
  if (abs >= 1000) return 1
  if (abs >= 1) return 4
  return 8
}

function toNumber(value?: Amount | null) {
  if (value === null || value === undefined || value === '') return null
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : null
}

function pnlTone(value?: Amount | null): PositionPnlTone {
  const numeric = toNumber(value)
  if (numeric === null || numeric === 0) return 'neutral'
  return numeric > 0 ? 'positive' : 'negative'
}

function settlementCurrency(position: PositionResponse) {
  const normalized = position.symbol.toUpperCase()
  const instrumentType = position.instrumentType?.toUpperCase()
  if (instrumentType === 'SWAP' && normalized.endsWith('USD') && !normalized.endsWith('USDT') && !normalized.endsWith('USDC')) {
    return normalized.slice(0, -3)
  }
  if (normalized.endsWith('USDT')) return 'USDT'
  if (normalized.endsWith('USD')) return 'USD'
  return ''
}

function isSpotPosition(position: PositionResponse) {
  return position.instrumentType?.toUpperCase() === 'SPOT'
}

function marginModeLabel(mode: string | null | undefined, t: Translate) {
  const normalized = mode?.toUpperCase()
  if (normalized === 'CROSS') return t('positions.marginModes.cross')
  if (normalized === 'ISOLATED') return t('positions.marginModes.isolated')
  return '--'
}
