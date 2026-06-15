import { useCallback, useEffect, useMemo, useState } from 'react'

import { getEnabledIndicatorNames, loadChartSettings, saveChartSettings, updateIndicatorEnabled } from './chartSettings'
import type { ChartSettings, ChartType, DrawingMagnetMode, DrawingTool, IndicatorSettings } from './chartSettings'
import type { ChartThemeMode, TradingChartCallbacks } from './tradingPageViewModels'
import type { TradingPeriod } from '../../features/market/tradingModels'

type TradingChartSettingsState = {
  chartCallbacks: TradingChartCallbacks
  chartSettings: ChartSettings
  chartThemeMode: ChartThemeMode
  indicators: string[]
}

export function useTradingChartSettings(
  selectedSymbol: string,
  colorScheme: string
): TradingChartSettingsState {
  const [chartSettings, setChartSettings] = useState<ChartSettings>(() => loadChartSettings(selectedSymbol))

  useEffect(() => {
    setChartSettings(loadChartSettings(selectedSymbol))
  }, [selectedSymbol])

  const updateChartSettings = useCallback(
    (updater: (current: ChartSettings) => ChartSettings) => {
      setChartSettings((current) => {
        const nextSettings = updater(current)
        saveChartSettings(selectedSymbol, nextSettings)
        return nextSettings
      })
    },
    [selectedSymbol]
  )

  const chartCallbacks = useMemo<TradingChartCallbacks>(
    () => ({
      onChartTypeChange: (chartType: ChartType) => {
        updateChartSettings((current) => ({ ...current, chartType }))
      },
      onDrawingMagnetModeChange: (magnetMode: DrawingMagnetMode) => {
        updateChartSettings((current) => ({
          ...current,
          drawingToolSettings: {
            ...current.drawingToolSettings,
            magnetMode
          }
        }))
      },
      onDrawingToolChange: (activeTool: DrawingTool) => {
        updateChartSettings((current) => ({
          ...current,
          drawingToolSettings: {
            ...current.drawingToolSettings,
            activeTool
          }
        }))
      },
      onIndicatorSettingsChange: (indicatorSettings: IndicatorSettings) => {
        updateChartSettings((current) => ({ ...current, indicatorSettings }))
      },
      onIndicatorToggle: (indicator: string) => {
        updateChartSettings((current) => ({
          ...current,
          indicatorSettings: updateIndicatorEnabled(
            current.indicatorSettings,
            indicator,
            !getEnabledIndicatorNames(current.indicatorSettings).includes(indicator)
          )
        }))
      },
      onPeriodChange: (interval: TradingPeriod) => {
        updateChartSettings((current) => ({ ...current, interval }))
      }
    }),
    [updateChartSettings]
  )
  const indicators = useMemo(
    () => getEnabledIndicatorNames(chartSettings.indicatorSettings),
    [chartSettings.indicatorSettings]
  )

  return {
    chartCallbacks,
    chartSettings,
    chartThemeMode: colorScheme === 'light' ? 'light' : 'dark',
    indicators
  }
}
