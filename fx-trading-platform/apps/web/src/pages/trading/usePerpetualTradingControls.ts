import { useMemo } from 'react'

import { useTradingSettings } from '../../features/trading-settings/useTradingSettings'
import type { OrderAdapterSettings } from '../../features/trading/services/orderAdapter'
import type { TradingInstrumentRules, TradingMarket } from '../../features/market/tradingModels'
import { usePerpetualReference } from './usePerpetualReference'

export type PerpetualTradingControlsModel = {
  visible: boolean
  adapterSettings: OrderAdapterSettings
  reference: ReturnType<typeof usePerpetualReference>
  positionMode: 'ONE_WAY' | 'HEDGE'
  marginMode: 'CROSS' | 'ISOLATED'
  leverage: number
  maxLeverage: number
  quantityUnit: 'BASE' | 'QUOTE' | 'CONTRACTS'
  disabled: boolean
  ready: boolean
  pending: boolean
  error: string | null
  onPositionModeChange: (value: 'ONE_WAY' | 'HEDGE') => void
  onMarginModeChange: (value: 'CROSS' | 'ISOLATED') => void
  onLeverageChange: (value: number) => void
  onQuantityUnitChange: (value: 'BASE' | 'QUOTE' | 'CONTRACTS') => void
}

type Options = {
  accountId?: string | null
  token?: string | null
  market: TradingMarket
  enabled: boolean
}

export function usePerpetualTradingControls({ accountId, token, market, enabled }: Options): PerpetualTradingControlsModel {
  const settingsState = useTradingSettings({ accountId, token, symbol: market.symbol, enabled })
  const reference = usePerpetualReference(market.symbol, enabled)
  const symbolSettings = settingsState.symbolSettings
  const positionMode = settingsState.settings?.positionMode ?? 'ONE_WAY'
  const marginMode = symbolSettings?.marginMode === 'ISOLATED' ? 'ISOLATED' : 'CROSS'
  const quantityUnit = resolveQuantityUnit(symbolSettings?.quantityUnit)
  const maxLeverage = resolveMaxLeverage(symbolSettings?.maxLeverage, market.rules)
  const leverage = clampNumber(symbolSettings?.leverage ?? market.leverage ?? 10, 1, maxLeverage)
  const disabled = !accountId || settingsState.loading || !symbolSettings
  const ready = !enabled || Boolean(accountId && symbolSettings && !settingsState.loading && !settingsState.saving)
  const error = formatControlError(settingsState.error)

  const adapterSettings = useMemo<OrderAdapterSettings>(() => ({
    leverage,
    positionMode,
    marginMode,
    quantityUnit
  }), [leverage, marginMode, positionMode, quantityUnit])

  return {
    visible: enabled,
    adapterSettings,
    reference,
    positionMode,
    marginMode,
    leverage,
    maxLeverage,
    quantityUnit,
    disabled,
    ready,
    pending: settingsState.saving,
    error,
    onPositionModeChange: (value) => void settingsState.setPositionMode(value).catch(() => undefined),
    onMarginModeChange: (value) => void settingsState.patchSymbolSettings({ marginMode: value }).catch(() => undefined),
    onLeverageChange: (value) => void settingsState.patchSymbolSettings({ leverage: value }).catch(() => undefined),
    onQuantityUnitChange: (value) => void settingsState.patchSymbolSettings({ quantityUnit: value }).catch(() => undefined)
  }
}

function resolveQuantityUnit(value: string | undefined): 'BASE' | 'QUOTE' | 'CONTRACTS' {
  return value === 'QUOTE' || value === 'CONTRACTS' ? value : 'BASE'
}

function resolveMaxLeverage(settingsMaximum: number | undefined, rules?: TradingInstrumentRules) {
  return clampNumber(settingsMaximum ?? rules?.maxLeverage ?? 100, 1, 100)
}

function clampNumber(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, Math.round(value)))
}

function formatControlError(error: unknown) {
  if (error instanceof Error) return error.message
  return typeof error === 'string' ? error : null
}
