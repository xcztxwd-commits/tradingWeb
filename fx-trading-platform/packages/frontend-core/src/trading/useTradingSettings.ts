import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type {
  MarginMode,
  PositionMode,
  QuantityUnit,
  TradingSettingsResponse,
  UpdateSymbolSettingsRequest
} from '@fx-platform/shared-types'
import {
  getTradingSettings,
  updateTradingPositionMode,
  updateTradingSymbolSettings
} from '../api/tradingApi.ts'

export type TradingSymbolSettings = NonNullable<TradingSettingsResponse['symbols']>[number]

export type UseTradingSettingsOptions = {
  accountId?: string | null
  symbol: string
  token?: string | null
  enabled?: boolean
}

export type SymbolSettingsPatch = {
  leverage?: number
  marginMode?: Exclude<MarginMode, 'CASH'>
  quantityUnit?: QuantityUnit
}

export type UseTradingSettingsResult = {
  settings: TradingSettingsResponse | null
  symbolSettings: TradingSymbolSettings | null
  loading: boolean
  saving: boolean
  error: unknown
  refresh: () => Promise<TradingSettingsResponse | null>
  setPositionMode: (positionMode: PositionMode) => Promise<TradingSettingsResponse>
  patchSymbolSettings: (patch: SymbolSettingsPatch) => Promise<TradingSettingsResponse>
}

export function useTradingSettings({
  accountId,
  symbol,
  token,
  enabled = true
}: UseTradingSettingsOptions): UseTradingSettingsResult {
  const [settings, setSettings] = useState<TradingSettingsResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const loadGateRef = useRef<ReturnType<typeof createTradingSettingsRequestGate> | null>(null)
  const mutationGateRef = useRef<ReturnType<typeof createTradingSettingsRequestGate> | null>(null)
  if (loadGateRef.current === null) loadGateRef.current = createTradingSettingsRequestGate()
  if (mutationGateRef.current === null) mutationGateRef.current = createTradingSettingsRequestGate()
  const loadGate = loadGateRef.current
  const mutationGate = mutationGateRef.current
  const activeSettings = useMemo(
    () => resolveActiveTradingSettings(settings, accountId),
    [accountId, settings]
  )
  const symbolSettings = useMemo(() => resolveSymbolSettings(activeSettings, symbol), [activeSettings, symbol])

  const refresh = useCallback(async () => {
    const request = loadGate.begin()
    if (!enabled || !accountId) {
      setSettings(null)
      setLoading(false)
      return null
    }
    setLoading(true)
    setError(null)
    try {
      const next = await getTradingSettings(accountId, token ?? undefined)
      if (loadGate.isCurrent(request)) setSettings(next)
      return next
    } catch (nextError) {
      if (loadGate.isCurrent(request)) setError(nextError)
      throw nextError
    } finally {
      if (loadGate.isCurrent(request)) setLoading(false)
    }
  }, [accountId, enabled, loadGate, token])

  useEffect(() => {
    setSaving(false)
    void refresh().catch(() => undefined)
    return () => {
      loadGate.invalidate()
      mutationGate.invalidate()
    }
  }, [loadGate, mutationGate, refresh])

  const setPositionMode = useCallback(async (positionMode: PositionMode) => {
    if (!accountId) throw new Error('Trading account is required')
    const request = mutationGate.begin()
    setSaving(true)
    setError(null)
    try {
      return await runTradingSettingsMutation(
        () => updateTradingPositionMode(accountId, positionMode, token ?? undefined),
        (next) => {
          if (mutationGate.isCurrent(request)) setSettings(next)
        },
        (nextError) => {
          if (mutationGate.isCurrent(request)) setError(nextError)
        }
      )
    } finally {
      if (mutationGate.isCurrent(request)) setSaving(false)
    }
  }, [accountId, mutationGate, token])

  const patchSymbolSettings = useCallback(async (patch: SymbolSettingsPatch) => {
    if (!accountId) throw new Error('Trading account is required')
    if (symbolSettings?.version === undefined) throw new Error(`Trading settings are unavailable for ${symbol}`)
    const payload = buildSymbolSettingsPayload(symbolSettings, patch)
    const request = mutationGate.begin()
    setSaving(true)
    setError(null)
    try {
      return await runTradingSettingsMutation(
        () => updateTradingSymbolSettings(accountId, symbol, payload, token ?? undefined),
        (next) => {
          if (mutationGate.isCurrent(request)) setSettings(next)
        },
        (nextError) => {
          if (mutationGate.isCurrent(request)) setError(nextError)
        }
      )
    } finally {
      if (mutationGate.isCurrent(request)) setSaving(false)
    }
  }, [accountId, mutationGate, symbol, symbolSettings, token])

  return {
    settings: activeSettings,
    symbolSettings,
    loading,
    saving,
    error,
    refresh,
    setPositionMode,
    patchSymbolSettings
  }
}

export function resolveActiveTradingSettings(
  settings: TradingSettingsResponse | null,
  accountId?: string | null
) {
  return accountId && settings?.accountId === accountId ? settings : null
}

export function resolveSymbolSettings(settings: TradingSettingsResponse | null, symbol: string) {
  const canonicalSymbol = symbol.trim().toUpperCase()
  return settings?.symbols?.find((item) => item.symbol?.toUpperCase() === canonicalSymbol) ?? null
}

export function clampTradingLeverage(requested: number, backendMax?: number) {
  const validBackendMax = backendMax !== undefined && Number.isFinite(backendMax) && backendMax > 0
    ? backendMax
    : 100
  const maxLeverage = Math.min(100, validBackendMax)
  const rounded = Number.isFinite(requested) ? Math.round(requested) : 1
  return Math.min(maxLeverage, Math.max(1, rounded))
}

export function createTradingSettingsRequestGate() {
  let generation = 0
  return {
    begin() {
      generation += 1
      return generation
    },
    isCurrent(request: number) {
      return request === generation
    },
    invalidate() {
      generation += 1
    }
  }
}

export function buildSymbolSettingsPayload(
  symbolSettings: TradingSymbolSettings,
  patch: SymbolSettingsPatch
): UpdateSymbolSettingsRequest {
  if (symbolSettings.version === undefined) {
    throw new Error(`Trading settings version is unavailable for ${symbolSettings.symbol ?? 'symbol'}`)
  }
  return {
    ...patch,
    leverage: patch.leverage === undefined
      ? undefined
      : clampTradingLeverage(patch.leverage, symbolSettings.maxLeverage),
    expectedVersion: symbolSettings.version
  }
}

export async function runTradingSettingsMutation<T>(
  mutation: () => Promise<T>,
  onSuccess: (value: T) => void,
  onError: (error: unknown) => void
) {
  try {
    const value = await mutation()
    onSuccess(value)
    return value
  } catch (error) {
    onError(error)
    throw error
  }
}
