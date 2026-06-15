export type TradingPanelId = 'watchlist' | 'header' | 'chart' | 'market' | 'trade' | 'bottom'

export type TradingDropSide = 'top' | 'right' | 'bottom' | 'left'

export type TradingSplitDirection = 'row' | 'column'

export type MainResizeHandle = 'chart-market' | 'trade-bottom'

export type TradingLayoutPreset = 'default' | 'chart-focus' | 'order-focus'
export type TradingLayoutPresetId = TradingLayoutPreset

export type TradingPanelNode = {
  type: 'panel'
  id: TradingPanelId
}

export type TradingSplitNode = {
  type: 'split'
  direction: TradingSplitDirection
  sizes: [number, number]
  children: [TradingLayoutNode, TradingLayoutNode]
}

export type TradingLayoutNode = TradingPanelNode | TradingSplitNode

export type TradingLayout = {
  root: TradingLayoutNode
}

export type TradingWorkspaceTemplates = {
  mainColumns: string
  centerRows: string
  bottomColumns: string
}

type StorageLike = {
  getItem: (key: string) => string | null
  setItem: (key: string, value: string) => void
}

type RemoveResult = {
  node: TradingLayoutNode | null
  removed: boolean
}

const MIN_SPLIT_SIZE = 14
const TERMINAL_BOTTOM_MIN_SPLIT_SIZE = 4
const HEADER_MIN_SPLIT_SIZE = 4
const MOVED_BRANCH_MIN_SIZE = 50
const RESIZE_HANDLE_SIZE = 8
const DEFAULT_OUTER_SIZES: [number, number] = [14, 86]
const DEFAULT_HEADER_STACK_SIZES: [number, number] = [4, 96]
const DEFAULT_WORKSPACE_SIZES: [number, number] = [76, 24]
const DEFAULT_MAIN_SIZES: [number, number] = [74, 26]
const DEFAULT_CENTER_STACK_SIZES: [number, number] = [58, 42]

const panelIds = ['watchlist', 'header', 'chart', 'market', 'trade', 'bottom'] as const
const panelIdSet = new Set<string>(panelIds)
const fixedPanelIds = new Set<TradingPanelId>(['header'])

export const TRADING_LAYOUT_STORAGE_KEY = 'fx.trading.workspace.layout.v8'

export const DEFAULT_TRADING_LAYOUT: TradingLayout = buildDefaultLayout(
  DEFAULT_WORKSPACE_SIZES,
  DEFAULT_MAIN_SIZES,
  DEFAULT_CENTER_STACK_SIZES
)

const presetLayouts: Record<TradingLayoutPreset, TradingLayout> = {
  default: DEFAULT_TRADING_LAYOUT,
  'chart-focus': buildDefaultLayout([78, 22], [80, 20], [68, 32]),
  'order-focus': buildDefaultLayout([74, 26], [70, 30], [48, 52])
}

export function loadTradingLayout(storage = getBrowserStorage()): TradingLayout {
  if (!storage) return cloneLayout(DEFAULT_TRADING_LAYOUT)

  try {
    return normalizeTradingLayout(JSON.parse(storage.getItem(TRADING_LAYOUT_STORAGE_KEY) ?? 'null'))
  } catch {
    return cloneLayout(DEFAULT_TRADING_LAYOUT)
  }
}

export function saveTradingLayout(storage: StorageLike | null | undefined, layout: TradingLayout) {
  if (!storage) return
  storage.setItem(TRADING_LAYOUT_STORAGE_KEY, JSON.stringify(normalizeTradingLayout(layout)))
}

export function moveTradingPanel(
  layout: TradingLayout,
  sourceId: TradingPanelId,
  targetId: TradingPanelId,
  side: TradingDropSide
): TradingLayout {
  const current = normalizeTradingLayout(layout)
  if (sourceId === targetId) return current
  if (fixedPanelIds.has(sourceId) || fixedPanelIds.has(targetId)) return current

  const removed = removePanel(current.root, sourceId)
  if (!removed.removed || !removed.node) return current

  return rebalanceMovedPanelBranch(
    normalizeTradingLayout({
      root: insertPanel(removed.node, sourceId, targetId, side)
    }),
    sourceId,
    side
  )
}

export function resizeTradingSplit(layout: TradingLayout, path: string, deltaPercent: number): TradingLayout {
  const current = normalizeTradingLayout(layout)
  if (!Number.isFinite(deltaPercent)) return current

  return normalizeTradingLayout({
    root: resizeSplitNode(current.root, getSplitPathIndexes(path), deltaPercent)
  })
}

export function resizeMainLayout(layout: TradingLayout, handle: MainResizeHandle, deltaPercent: number): TradingLayout {
  return resizeTradingSplit(layout, handle === 'chart-market' ? 'root.1.1.0' : 'root.1.1', deltaPercent)
}

export function resizeCenterLayout(layout: TradingLayout, deltaPercent: number): TradingLayout {
  return resizeTradingSplit(layout, 'root.1.1.0.0', deltaPercent)
}

export function applyTradingLayoutPreset(preset: TradingLayoutPresetId): TradingLayout {
  return cloneLayout(presetLayouts[preset])
}

export function resetTradingLayout() {
  return cloneLayout(DEFAULT_TRADING_LAYOUT)
}

export function normalizeTradingLayout(value: unknown): TradingLayout {
  if (!isRecord(value)) return cloneLayout(DEFAULT_TRADING_LAYOUT)

  const root = normalizeNode(value.root)
  if (!root) return cloneLayout(DEFAULT_TRADING_LAYOUT)

  const ids = getPanelIds(root)
  if (ids.length !== panelIds.length || new Set(ids).size !== panelIds.length) return cloneLayout(DEFAULT_TRADING_LAYOUT)
  if (!panelIds.every((id) => ids.includes(id))) return cloneLayout(DEFAULT_TRADING_LAYOUT)

  return { root }
}

export function getTradingLayoutPanelIds(layout: TradingLayout) {
  return getPanelIds(normalizeTradingLayout(layout).root)
}

export function getTradingWorkspaceTemplates(layout: TradingLayout): TradingWorkspaceTemplates {
  const current = normalizeTradingLayout(layout)

  return {
    mainColumns: splitTemplate(getSplitSizes(current.root, 'root.1.1.0', DEFAULT_MAIN_SIZES)),
    centerRows: splitTemplate(getSplitSizes(current.root, 'root.1.1.0.0', DEFAULT_CENTER_STACK_SIZES)),
    bottomColumns: splitTemplate(getSplitSizes(current.root, 'root.1.1', DEFAULT_WORKSPACE_SIZES))
  }
}

function removePanel(node: TradingLayoutNode, panelId: TradingPanelId): RemoveResult {
  if (node.type === 'panel') {
    return {
      node: node.id === panelId ? null : node,
      removed: node.id === panelId
    }
  }

  const first = removePanel(node.children[0], panelId)
  if (first.removed) {
    return {
      node: first.node ? { ...node, children: [first.node, node.children[1]] } : node.children[1],
      removed: true
    }
  }

  const second = removePanel(node.children[1], panelId)
  if (second.removed) {
    return {
      node: second.node ? { ...node, children: [node.children[0], second.node] } : node.children[0],
      removed: true
    }
  }

  return { node, removed: false }
}

function insertPanel(
  node: TradingLayoutNode,
  sourceId: TradingPanelId,
  targetId: TradingPanelId,
  side: TradingDropSide
): TradingLayoutNode {
  if (node.type === 'panel') {
    if (node.id !== targetId) return node

    const source: TradingPanelNode = { type: 'panel', id: sourceId }
    const direction: TradingSplitDirection = side === 'left' || side === 'right' ? 'row' : 'column'
    const children: [TradingLayoutNode, TradingLayoutNode] =
      side === 'left' || side === 'top' ? [source, node] : [node, source]

    return {
      type: 'split',
      direction,
      sizes: [50, 50],
      children
    }
  }

  return {
    ...node,
    children: [
      insertPanel(node.children[0], sourceId, targetId, side),
      insertPanel(node.children[1], sourceId, targetId, side)
    ]
  }
}

function resizeSplitNode(node: TradingLayoutNode, path: number[], deltaPercent: number): TradingLayoutNode {
  if (node.type === 'panel') return node

  if (path.length === 0) {
    const minSplitSize = getMinSplitSize(node)
    const first = clamp(round(node.sizes[0] + deltaPercent), minSplitSize, 100 - minSplitSize)
    return {
      ...node,
      sizes: [first, round(100 - first)]
    }
  }

  const [index, ...rest] = path
  if (index !== 0 && index !== 1) return node

  return {
    ...node,
    children: [
      index === 0 ? resizeSplitNode(node.children[0], rest, deltaPercent) : node.children[0],
      index === 1 ? resizeSplitNode(node.children[1], rest, deltaPercent) : node.children[1]
    ]
  }
}

function rebalanceMovedPanelBranch(
  layout: TradingLayout,
  panelId: TradingPanelId,
  side: TradingDropSide
): TradingLayout {
  const path = getPanelPath(layout.root, panelId)
  if (!path) return layout

  return normalizeTradingLayout({
    root: rebalanceNode(layout.root, path, side === 'left' || side === 'right' ? 'row' : 'column')
  })
}

function rebalanceNode(
  node: TradingLayoutNode,
  panelPath: number[],
  direction: TradingSplitDirection
): TradingLayoutNode {
  if (node.type === 'panel' || panelPath.length === 0) return node

  const [index, ...rest] = panelPath
  if (index !== 0 && index !== 1) return node

  const children: [TradingLayoutNode, TradingLayoutNode] = [
    index === 0 ? rebalanceNode(node.children[0], rest, direction) : node.children[0],
    index === 1 ? rebalanceNode(node.children[1], rest, direction) : node.children[1]
  ]

  if (node.direction !== direction || node.sizes[index] >= MOVED_BRANCH_MIN_SIZE) {
    return { ...node, children }
  }

  const sibling = index === 0 ? 1 : 0
  const delta = Math.min(MOVED_BRANCH_MIN_SIZE - node.sizes[index], node.sizes[sibling] - MIN_SPLIT_SIZE)
  if (delta <= 0) return { ...node, children }

  const sizes: [number, number] = [node.sizes[0], node.sizes[1]]
  sizes[index] = round(sizes[index] + delta)
  sizes[sibling] = round(sizes[sibling] - delta)

  return { ...node, sizes, children }
}

function normalizeNode(value: unknown): TradingLayoutNode | null {
  if (!isRecord(value)) return null

  if (value.type === 'panel') {
    return typeof value.id === 'string' && panelIdSet.has(value.id)
      ? { type: 'panel', id: value.id as TradingPanelId }
      : null
  }

  if (value.type !== 'split' || (value.direction !== 'row' && value.direction !== 'column')) return null
  const direction = value.direction
  if (!Array.isArray(value.children) || value.children.length !== 2) return null
  if (!Array.isArray(value.sizes) || value.sizes.length !== 2) return null
  if (!isFiniteNumber(value.sizes[0]) || !isFiniteNumber(value.sizes[1])) return null

  const firstChild = normalizeNode(value.children[0])
  const secondChild = normalizeNode(value.children[1])
  if (!firstChild || !secondChild) return null

  const total = value.sizes[0] + value.sizes[1]
  if (total <= 0) return null

  const children: [TradingLayoutNode, TradingLayoutNode] = [firstChild, secondChild]
  const minSplitSize = getMinSplitSize({ type: 'split', direction, sizes: [50, 50], children })
  const first = clamp(round((value.sizes[0] / total) * 100), minSplitSize, 100 - minSplitSize)

  return {
    type: 'split',
    direction,
    sizes: [first, round(100 - first)],
    children
  }
}

function getMinSplitSize(node: TradingSplitNode) {
  if (isHeaderTopSplit(node)) return HEADER_MIN_SPLIT_SIZE
  return isTerminalBottomSplit(node) ? TERMINAL_BOTTOM_MIN_SPLIT_SIZE : MIN_SPLIT_SIZE
}

function isTerminalBottomSplit(node: TradingSplitNode) {
  const terminal = node.children[1]
  return node.direction === 'column' && terminal.type === 'panel' && terminal.id === 'bottom'
}

function isHeaderTopSplit(node: TradingSplitNode) {
  const top = node.children[0]
  return node.direction === 'column' && top.type === 'panel' && top.id === 'header'
}

function getPanelIds(node: TradingLayoutNode): TradingPanelId[] {
  if (node.type === 'panel') return [node.id]
  return [...getPanelIds(node.children[0]), ...getPanelIds(node.children[1])]
}

function getPanelPath(node: TradingLayoutNode, panelId: TradingPanelId): number[] | null {
  if (node.type === 'panel') return node.id === panelId ? [] : null

  const first = getPanelPath(node.children[0], panelId)
  if (first) return [0, ...first]

  const second = getPanelPath(node.children[1], panelId)
  return second ? [1, ...second] : null
}

function getSplitPathIndexes(path: string) {
  if (path === 'root') return []
  if (!path.startsWith('root.')) return []

  return path
    .slice(5)
    .split('.')
    .map((part) => Number(part))
    .filter((part) => part === 0 || part === 1)
}

function getSplitSizes(node: TradingLayoutNode, path: string, fallback: [number, number]): [number, number] {
  const split = getSplitNode(node, getSplitPathIndexes(path))
  return split?.sizes ?? fallback
}

function getSplitNode(node: TradingLayoutNode, path: number[]): TradingSplitNode | null {
  if (node.type === 'panel') return null
  if (path.length === 0) return node

  const [index, ...rest] = path
  if (index !== 0 && index !== 1) return null
  return getSplitNode(node.children[index], rest)
}

function splitTemplate([first, second]: [number, number]) {
  return `minmax(0, ${first}fr) ${RESIZE_HANDLE_SIZE}px minmax(0, ${second}fr)`
}

function buildDefaultLayout(
  workspaceSizes: [number, number],
  mainSizes: [number, number],
  centerSizes: [number, number]
): TradingLayout {
  return {
    root: {
      type: 'split',
      direction: 'row',
      sizes: DEFAULT_OUTER_SIZES,
      children: [
        { type: 'panel', id: 'watchlist' },
        {
          type: 'split',
          direction: 'column',
          sizes: DEFAULT_HEADER_STACK_SIZES,
          children: [
            { type: 'panel', id: 'header' },
            {
              type: 'split',
              direction: 'column',
              sizes: workspaceSizes,
              children: [
                {
                  type: 'split',
                  direction: 'row',
                  sizes: mainSizes,
                  children: [
                    {
                      type: 'split',
                      direction: 'column',
                      sizes: centerSizes,
                      children: [
                        { type: 'panel', id: 'chart' },
                        { type: 'panel', id: 'trade' }
                      ]
                    },
                    { type: 'panel', id: 'market' }
                  ]
                },
                { type: 'panel', id: 'bottom' }
              ]
            }
          ]
        }
      ]
    }
  }
}

function getBrowserStorage() {
  if (typeof window === 'undefined') return null
  return window.localStorage
}

function cloneLayout(layout: TradingLayout): TradingLayout {
  return JSON.parse(JSON.stringify(layout)) as TradingLayout
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

function round(value: number) {
  return Math.round(value * 100) / 100
}
