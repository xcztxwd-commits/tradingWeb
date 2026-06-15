import { useCallback, useRef, useState } from 'react'

import {
  DEFAULT_TRADING_LAYOUT,
  applyTradingLayoutPreset,
  loadTradingLayout,
  moveTradingPanel,
  resizeTradingSplit,
  resetTradingLayout,
  saveTradingLayout,
  type TradingDropSide,
  type TradingLayout,
  type TradingLayoutPreset,
  type TradingPanelId,
  type TradingSplitDirection
} from '../stores/layoutStore'

type ResizeDelta = {
  deltaX: number
  deltaY: number
}

type ActiveResize = {
  path: string
  direction: TradingSplitDirection
  size: number
  startLayout: TradingLayout
}

export function useResizableLayout() {
  const [layout, setLayoutState] = useState<TradingLayout>(() => loadTradingLayout())
  const [activePreset, setActivePreset] = useState<TradingLayoutPreset | null>('default')
  const layoutRef = useRef(layout)
  const activeResizeRef = useRef<ActiveResize | null>(null)

  const setLayout = useCallback((nextLayout: TradingLayout) => {
    layoutRef.current = nextLayout
    setLayoutState(nextLayout)
  }, [])

  const beginSplitResize = useCallback((path: string, direction: TradingSplitDirection, containerSize: number) => {
    if (containerSize <= 0) return
    activeResizeRef.current = {
      path,
      direction,
      size: containerSize,
      startLayout: layoutRef.current
    }
  }, [])

  const resizeByDelta = useCallback(
    ({ deltaX, deltaY }: ResizeDelta) => {
      const activeResize = activeResizeRef.current
      if (!activeResize) return

      const delta = activeResize.direction === 'row' ? deltaX : deltaY
      setActivePreset(null)
      setLayout(resizeTradingSplit(activeResize.startLayout, activeResize.path, (delta / activeResize.size) * 100))
    },
    [setLayout]
  )

  const endResize = useCallback(() => {
    activeResizeRef.current = null
    saveTradingLayout(getBrowserStorage(), layoutRef.current)
  }, [])

  const movePanel = useCallback(
    (sourceId: TradingPanelId, targetId: TradingPanelId, side: TradingDropSide) => {
      const nextLayout = moveTradingPanel(layoutRef.current, sourceId, targetId, side)
      setActivePreset(null)
      setLayout(nextLayout)
      saveTradingLayout(getBrowserStorage(), nextLayout)
    },
    [setLayout]
  )

  const resetLayout = useCallback(() => {
    const nextLayout = resetTradingLayout()
    setActivePreset('default')
    setLayout(nextLayout)
    saveTradingLayout(getBrowserStorage(), nextLayout)
  }, [setLayout])

  const applyPreset = useCallback(
    (preset: TradingLayoutPreset) => {
      const nextLayout = applyTradingLayoutPreset(preset)
      setActivePreset(preset)
      setLayout(nextLayout)
      saveTradingLayout(getBrowserStorage(), nextLayout)
    },
    [setLayout]
  )

  return {
    layout,
    activePreset,
    applyPreset,
    beginSplitResize,
    resizeByDelta,
    endResize,
    movePanel,
    resetLayout
  }
}

function getBrowserStorage() {
  if (typeof window === 'undefined') return null
  return window.localStorage
}

export { DEFAULT_TRADING_LAYOUT }
