import { useCallback, useMemo, useState } from 'react'

import {
  defaultChartSettings,
  getEnabledIndicatorNames,
  loadChartSettingsForSymbol,
  saveChartSettings,
  selectChartSettingsForSymbol,
  toggleFavoriteInterval,
  updateIndicatorEnabled
} from './chartSettings'
import type {
  ChartSettings,
  ChartType,
  DrawingMagnetMode,
  DrawingTool,
  IndicatorSettings,
  OwnedChartSettingsState
} from './chartSettings'
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
  const [chartSettingsState, setChartSettingsState] = useState<OwnedChartSettingsState>(() => ({
    ownerSymbol: selectedSymbol,
    settings: loadChartSettingsForSymbol(selectedSymbol)
  }))
  const selectedChartSettingsState = selectChartSettingsForSymbol(
    chartSettingsState,
    selectedSymbol
  )
  if (selectedChartSettingsState !== chartSettingsState) {
    setChartSettingsState(selectedChartSettingsState)
  }

  const updateChartSettings = useCallback(
    (updater: (current: ChartSettings) => ChartSettings) => {
      setChartSettingsState((current) => {
        const selectedState = selectChartSettingsForSymbol(current, selectedSymbol)
        const nextSettings = updater(selectedState.settings)
        saveChartSettings(selectedSymbol, nextSettings)
        return {
          ownerSymbol: selectedSymbol,
          settings: nextSettings
        }
      })
    },
    [selectedSymbol]
  )

  const chartCallbacks = useMemo<TradingChartCallbacks>(
    () => ({
      onChartSettingsChange: (settings: ChartSettings) => {
        updateChartSettings(() => settings)
      },
      onResetChartSettings: () => {
        updateChartSettings(() => cloneChartSettings(defaultChartSettings))
      },
      onChartTypeChange: (chartType: ChartType) => {
        updateChartSettings((current) => ({ ...current, chartType }))
      },
      onHighLowPriceMarksChange: (highLowPriceMarks: boolean) => {
        updateChartSettings((current) => ({
          ...current,
          axisSettings: {
            ...current.axisSettings,
            highPriceMark: highLowPriceMarks,
            lowPriceMark: highLowPriceMarks,
            highLowPriceMarks
          }
        }))
      },
      onPriceScaleModeChange: (priceScaleMode: ChartSettings['axisSettings']['priceScaleMode']) => {
        updateChartSettings((current) => ({
          ...current,
          axisSettings: {
            ...current.axisSettings,
            priceScaleMode
          }
        }))
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
      onFavoriteIntervalToggle: (interval: TradingPeriod) => {
        updateChartSettings((current) => ({
          ...current,
          favoriteIntervals: toggleFavoriteInterval(current.favoriteIntervals, interval)
        }))
      },
      onPeriodChange: (interval: TradingPeriod) => {
        updateChartSettings((current) => ({ ...current, interval }))
      }
    }),
    [updateChartSettings]
  )
  const indicators = useMemo(
    () => getEnabledIndicatorNames(selectedChartSettingsState.settings.indicatorSettings),
    [selectedChartSettingsState.settings.indicatorSettings]
  )

  return {
    chartCallbacks,
    chartSettings: selectedChartSettingsState.settings,
    chartThemeMode: colorScheme === 'light' ? 'light' : 'dark',
    indicators
  }
}

function cloneChartSettings(settings: ChartSettings): ChartSettings {
  return JSON.parse(JSON.stringify(settings)) as ChartSettings
}
