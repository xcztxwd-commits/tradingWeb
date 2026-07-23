import { GripVertical } from 'lucide-react'
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type DragEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

import type {
  TradingDropSide,
  TradingLayout,
  TradingLayoutNode,
  TradingLayoutPreset,
  TradingPanelId,
  TradingPanelNode,
  TradingSplitDirection
} from './layoutStore'
import { ResizablePanel } from './ResizablePanel'
import { ResizeHandle } from './ResizeHandle'
import styles from './TradingWorkspace.module.css'

export type TradingWorkspaceLayoutControls = {
  layout: TradingLayout
  activePreset: TradingLayoutPreset | null
  applyPreset: (preset: TradingLayoutPreset) => void
  beginSplitResize: (path: string, direction: TradingSplitDirection, containerSize: number) => void
  resizeByDelta: (delta: { deltaX: number; deltaY: number }) => void
  endResize: () => void
  movePanel: (sourceId: TradingPanelId, targetId: TradingPanelId, side: TradingDropSide) => void
  resetLayout: () => void
  resetSignal: number
}

type Props = {
  watchlist: ReactNode
  header: ReactNode
  chart: ReactNode
  market: ReactNode
  trade: ReactNode
  bottom: ReactNode
  layoutControls: TradingWorkspaceLayoutControls
}

const panelClasses = {
  watchlist: styles.watchlistPanel,
  header: styles.headerPanel,
  chart: styles.chartPanel,
  market: styles.marketPanel,
  trade: styles.tradePanel,
  bottom: styles.accountPanel
} satisfies Record<TradingPanelId, string>

const panelLabelKeys = {
  watchlist: 'trading.panelLabels.watchlist',
  header: 'trading.panelLabels.header',
  chart: 'trading.panelLabels.chart',
  market: 'trading.panelLabels.market',
  trade: 'trading.panelLabels.trade',
  bottom: 'trading.panelLabels.bottom'
} satisfies Record<TradingPanelId, string>

const lockedPanelIds = new Set<TradingPanelId>(['header'])

const BOTTOM_PANEL_MIN_HEIGHT = 80
const BOTTOM_PANEL_AUTO_GROW_EDGE_DISTANCE = 24
const BOTTOM_PANEL_AUTO_GROW_STEP = 8
const BOTTOM_PANEL_RESIZE_HANDLE_HEIGHT = 8
const BOTTOM_PANEL_VISIBLE_TABLE_ROWS = 3
const BOTTOM_PANEL_TABLE_ROW_HEIGHT = 34
const BOTTOM_PANEL_TABS_HEIGHT = 38
const BOTTOM_PANEL_TOOLBAR_HEIGHT = 34
const BOTTOM_PANEL_INITIAL_HEIGHT =
  BOTTOM_PANEL_TABS_HEIGHT + BOTTOM_PANEL_TOOLBAR_HEIGHT + BOTTOM_PANEL_TABLE_ROW_HEIGHT * (BOTTOM_PANEL_VISIBLE_TABLE_ROWS + 1)

type ActiveDrop = {
  panelId: TradingPanelId
  side: TradingDropSide
}

export function TradingWorkspace({ watchlist, header, chart, market, trade, bottom, layoutControls }: Props) {
  const { t } = useTranslation()
  const { layout, beginSplitResize, resizeByDelta, endResize, movePanel, resetLayout, resetSignal } = layoutControls
  const splitRefs = useRef<Record<string, HTMLDivElement | null>>({})
  const panelShellRefs = useRef<Partial<Record<TradingPanelId, HTMLDivElement | null>>>({})
  const draggedPanelIdRef = useRef<TradingPanelId | null>(null)
  const bottomPanelHeightDragStartRef = useRef(BOTTOM_PANEL_MIN_HEIGHT)
  const bottomEdgePointerYRef = useRef(0)
  const bottomEdgeDraggingRef = useRef(false)
  const bottomEdgeAutoGrowFrameRef = useRef(0)
  const bottomEdgeAutoGrowExtraRef = useRef(0)
  const initialSizingAppliedRef = useRef(false)
  const previousResetSignalRef = useRef(resetSignal)
  const [activeDrop, setActiveDrop] = useState<ActiveDrop | null>(null)
  const [bottomPanelTargetHeight, setBottomPanelTargetHeight] = useState<number | null>(null)
  const panelContent = { watchlist, header, chart, market, trade, bottom } satisfies Record<TradingPanelId, ReactNode>

  const stopBottomEdgeAutoGrow = useCallback(() => {
    if (!bottomEdgeAutoGrowFrameRef.current) return
    window.cancelAnimationFrame(bottomEdgeAutoGrowFrameRef.current)
    bottomEdgeAutoGrowFrameRef.current = 0
  }, [])

  const runBottomEdgeAutoGrow = useCallback(() => {
    bottomEdgeAutoGrowFrameRef.current = 0
    if (!bottomEdgeDraggingRef.current) return

    if (window.innerHeight - bottomEdgePointerYRef.current <= BOTTOM_PANEL_AUTO_GROW_EDGE_DISTANCE) {
      bottomEdgeAutoGrowExtraRef.current += BOTTOM_PANEL_AUTO_GROW_STEP
      setBottomPanelTargetHeight((current) =>
        Math.max(BOTTOM_PANEL_MIN_HEIGHT, (current ?? bottomPanelHeightDragStartRef.current) + BOTTOM_PANEL_AUTO_GROW_STEP)
      )
    }

    bottomEdgeAutoGrowFrameRef.current = window.requestAnimationFrame(runBottomEdgeAutoGrow)
  }, [])

  const applyInitialPanelSizing = useCallback(() => {
    if (initialSizingAppliedRef.current) return true

    const bottomPanelHeight = panelShellRefs.current.bottom?.getBoundingClientRect().height ?? 0
    if (bottomPanelHeight <= 0) return false

    setBottomPanelTargetHeight(BOTTOM_PANEL_INITIAL_HEIGHT)
    initialSizingAppliedRef.current = true
    return true
  }, [])

  const resetBottomPanelSizing = useCallback(() => {
    initialSizingAppliedRef.current = false
    bottomEdgeAutoGrowExtraRef.current = 0
    setBottomPanelTargetHeight(null)
  }, [])

  const handleResetLayout = useCallback(() => {
    resetBottomPanelSizing()
    resetLayout()
  }, [resetBottomPanelSizing, resetLayout])

  useEffect(() => {
    return () => {
      stopBottomEdgeAutoGrow()
    }
  }, [stopBottomEdgeAutoGrow])

  useEffect(() => {
    if (previousResetSignalRef.current === resetSignal) return
    previousResetSignalRef.current = resetSignal
    resetBottomPanelSizing()
  }, [resetBottomPanelSizing, resetSignal])

  useLayoutEffect(() => {
    applyInitialPanelSizing()
    let retryCount = 0
    let frameId = 0

    const applyWhenReady = () => {
      frameId = 0
      if (!applyInitialPanelSizing()) {
        if (retryCount >= 8) return
        retryCount += 1
        frameId = window.requestAnimationFrame(applyWhenReady)
      }
    }

    frameId = window.requestAnimationFrame(applyWhenReady)

    return () => {
      if (frameId) window.cancelAnimationFrame(frameId)
    }
  }, [applyInitialPanelSizing])

  const renderLayoutNode = (node: TradingLayoutNode, path: string): ReactNode => {
    if (node.type === 'split') {
      const style = getSplitStyle(node, bottomPanelTargetHeight)
      const firstPath = `${path}.0`
      const secondPath = `${path}.1`
      const isBottomTerminalSplit = isTerminalBottomSplit(node)

      return (
        <div
          key={path}
          ref={(element) => {
            splitRefs.current[path] = element
          }}
          className={`${styles.split} ${styles[node.direction]}`}
          style={style}
        >
          {renderLayoutNode(node.children[0], firstPath)}
          <ResizeHandle
            ariaLabel={t('trading.resizeWorkspacePanels')}
            orientation={node.direction === 'row' ? 'vertical' : 'horizontal'}
            onDragStart={() => {
              const rect = splitRefs.current[path]?.getBoundingClientRect()
              beginSplitResize(path, node.direction, node.direction === 'row' ? rect?.width ?? 0 : rect?.height ?? 0)
              if (isBottomTerminalSplit) {
                const terminalHeight = panelShellRefs.current.bottom?.getBoundingClientRect().height ?? BOTTOM_PANEL_MIN_HEIGHT
                bottomPanelHeightDragStartRef.current = terminalHeight
                setBottomPanelTargetHeight(Math.max(BOTTOM_PANEL_MIN_HEIGHT, Math.round(terminalHeight)))
              }
            }}
            onDrag={(delta) => {
              if (isBottomTerminalSplit) {
                setBottomPanelTargetHeight(Math.max(BOTTOM_PANEL_MIN_HEIGHT, Math.round(bottomPanelHeightDragStartRef.current - delta.deltaY)))
                return
              }

              resizeByDelta(delta)
            }}
            onDragEnd={() => {
              endResize()
            }}
            onReset={handleResetLayout}
          />
          {renderLayoutNode(node.children[1], secondPath)}
        </div>
      )
    }

    const isBottomPanel = node.id === 'bottom'
    const isLockedPanel = lockedPanelIds.has(node.id)

    const panel = (
      <ResizablePanel
        ariaLabel={t(panelLabelKeys[node.id])}
        className={`${styles.panelFrame} ${panelClasses[node.id]}`}
      >
        <div
          ref={(element) => {
            panelShellRefs.current[node.id] = element
          }}
          className={styles.panelShell}
          data-panel-id={node.id}
          onDragLeave={(event) => {
            if (isLockedPanel) return
            if (event.currentTarget.contains(event.relatedTarget as Node | null)) return
            setActiveDrop(null)
          }}
          onDragOver={(event) => {
            if (isLockedPanel) return
            const draggedPanelId = getDraggedPanelId(draggedPanelIdRef.current ?? event.dataTransfer.getData('text/plain'))
            if (!draggedPanelId || draggedPanelId === node.id) return
            event.preventDefault()
            event.dataTransfer.dropEffect = 'move'
            setActiveDrop({ panelId: node.id, side: detectDropSide(event) })
          }}
          onDrop={(event) => {
            if (isLockedPanel) return
            handlePanelDrop(node, event)
          }}
        >
          {!isLockedPanel ? (
            <button
              type="button"
              className={styles.dragHandle}
              title={t('trading.dragPanel')}
              aria-label={t('trading.dragPanelAria', { panel: t(panelLabelKeys[node.id]) })}
              draggable
              onDragStart={(event) => handlePanelDragStart(node.id, event)}
              onDragEnd={() => {
                draggedPanelIdRef.current = null
                setActiveDrop(null)
              }}
            >
              <GripVertical size={14} />
            </button>
          ) : null}
          {activeDrop?.panelId === node.id ? <span className={`${styles.dropIndicator} ${styles[activeDrop.side]}`} /> : null}
          <div className={styles.panelContent}>
            {panelContent[node.id]}
          </div>
        </div>
      </ResizablePanel>
    )

    if (!isBottomPanel) return panel

    return (
      <div key={path} className={styles.bottomTerminal}>
        {panel}
        <ResizeHandle
          ariaLabel={t('trading.extendBottomPanel')}
          orientation="horizontal"
          onDragStart={() => {
            bottomPanelHeightDragStartRef.current =
              panelShellRefs.current.bottom?.getBoundingClientRect().height ?? bottomPanelTargetHeight ?? BOTTOM_PANEL_MIN_HEIGHT
            bottomEdgePointerYRef.current = 0
            bottomEdgeAutoGrowExtraRef.current = 0
            stopBottomEdgeAutoGrow()
            bottomEdgeDraggingRef.current = true
            bottomEdgeAutoGrowFrameRef.current = window.requestAnimationFrame(runBottomEdgeAutoGrow)
          }}
          onDrag={({ deltaY, clientY }) => {
            bottomEdgePointerYRef.current = clientY
            setBottomPanelTargetHeight(
              Math.max(BOTTOM_PANEL_MIN_HEIGHT, Math.round(bottomPanelHeightDragStartRef.current + deltaY + bottomEdgeAutoGrowExtraRef.current))
            )
          }}
          onDragEnd={() => {
            bottomEdgeDraggingRef.current = false
            stopBottomEdgeAutoGrow()
          }}
          onReset={() => {
            bottomEdgeAutoGrowExtraRef.current = 0
            setBottomPanelTargetHeight(BOTTOM_PANEL_INITIAL_HEIGHT)
          }}
        />
      </div>
    )
  }

  const handlePanelDragStart = (panelId: TradingPanelId, event: DragEvent<HTMLButtonElement>) => {
    draggedPanelIdRef.current = panelId
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', panelId)
  }

  const handlePanelDrop = (panel: TradingPanelNode, event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    const sourceId = getDraggedPanelId(draggedPanelIdRef.current ?? event.dataTransfer.getData('text/plain'))
    draggedPanelIdRef.current = null
    setActiveDrop(null)
    if (!sourceId || sourceId === panel.id) return

    movePanel(sourceId, panel.id, detectDropSide(event))
  }

  return (
    <section className={styles.workspace} aria-label={t('trading.workspace')}>
      <div className={styles.grid}>
        {renderLayoutNode(layout.root, 'root')}
      </div>
    </section>
  )
}

function getSplitStyle(node: Extract<TradingLayoutNode, { type: 'split' }>, bottomPanelTargetHeight: number | null): CSSProperties {
  if (isTerminalBottomSplit(node)) {
    if (bottomPanelTargetHeight !== null) {
      return {
        gridTemplateRows: `minmax(0, 1fr) 8px ${Math.max(BOTTOM_PANEL_MIN_HEIGHT, bottomPanelTargetHeight) + BOTTOM_PANEL_RESIZE_HANDLE_HEIGHT}px`
      }
    }

    return {
      gridTemplateRows: `minmax(0, ${node.sizes[0]}fr) 8px minmax(${BOTTOM_PANEL_MIN_HEIGHT}px, ${node.sizes[1]}fr)`
    }
  }

  const template = `minmax(0, ${node.sizes[0]}fr) 8px minmax(0, ${node.sizes[1]}fr)`
  return node.direction === 'row' ? { gridTemplateColumns: template } : { gridTemplateRows: template }
}

function isTerminalBottomSplit(node: Extract<TradingLayoutNode, { type: 'split' }>) {
  const terminal = node.children[1]
  return node.direction === 'column' && terminal.type === 'panel' && terminal.id === 'bottom'
}

function detectDropSide(event: DragEvent<HTMLElement>): TradingDropSide {
  const rect = event.currentTarget.getBoundingClientRect()
  const x = rect.width > 0 ? (event.clientX - rect.left) / rect.width : 0.5
  const y = rect.height > 0 ? (event.clientY - rect.top) / rect.height : 0.5
  const distances = [
    { side: 'left' as const, value: x },
    { side: 'right' as const, value: 1 - x },
    { side: 'top' as const, value: y },
    { side: 'bottom' as const, value: 1 - y }
  ]

  return distances.sort((first, second) => first.value - second.value)[0].side
}

function getDraggedPanelId(value: string | null): TradingPanelId | null {
  return value === 'watchlist' || value === 'chart' || value === 'market' || value === 'trade' || value === 'bottom'
    ? value
    : null
}
