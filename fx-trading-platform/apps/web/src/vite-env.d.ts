/// <reference types="vite/client" />

declare module 'klinecharts' {
  type KLineData = {
    timestamp: number
    open: number
    high: number
    low: number
    close: number
    volume?: number
  }

  type KLinePeriod = { type: 'second' | 'minute' | 'hour' | 'day' | 'week' | 'month' | 'year'; span: number }
  type IndicatorFigure = { key: string; title: string; type: string }
  type IndicatorTemplate<D = unknown, C = unknown> = {
    name: string
    shortName?: string
    series?: string
    calcParams?: C[]
    precision?: number
    shouldOhlc?: boolean
    figures?: IndicatorFigure[]
    regenerateFigures?: (params: C[]) => IndicatorFigure[]
    calc?: (dataList: KLineData[], indicator: { calcParams: C[]; figures: IndicatorFigure[] }) => D[]
  }
  type IndicatorCreate = {
    name: string
    calcParams?: number[]
    styles?: Record<string, unknown>
  }
  type OverlayCoordinate = { x: number; y: number }
  type OverlayBounding = { width: number; height: number }
  type OverlayFigure = {
    type: string
    attrs: unknown
    isCheckEvent?: boolean
  }
  type OverlayTemplate = {
    name: string
    totalStep?: number
    needDefaultPointFigure?: boolean
    needDefaultXAxisFigure?: boolean
    needDefaultYAxisFigure?: boolean
    createPointFigures?: (params: { coordinates: OverlayCoordinate[]; bounding: OverlayBounding }) => OverlayFigure | OverlayFigure[]
  }
  type OverlayMode = 'normal' | 'weak_magnet' | 'strong_magnet'
  type OverlayCreate = {
    name: string
    id?: string
    groupId?: string
    paneId?: string
    lock?: boolean
    mode?: OverlayMode
    modeSensitivity?: number
    extendData?: unknown
    visible?: boolean
    styles?: Record<string, unknown>
    onDrawEnd?: (event: unknown) => void
    onSelected?: (event: OverlayEvent) => void
    onDeselected?: (event: OverlayEvent) => void
    onClick?: (event: OverlayEvent) => void
  }
  type OverlayFilter = {
    id?: string
    groupId?: string
    name?: string
    paneId?: string
  }
  type OverlayEvent = {
    overlay: {
      id: string
      name: string
      lock?: boolean
      styles?: Record<string, unknown> | null
    }
    x?: number
    y?: number
    pageX?: number
    pageY?: number
  }

  type Chart = {
    setSymbol: (symbol: { ticker: string; name?: string; pricePrecision?: number; volumePrecision?: number }) => void
    setPeriod: (period: KLinePeriod) => void
    setStyles: (styles: string | Record<string, unknown>) => void
    createIndicator: (
      indicator: string | IndicatorCreate,
      options?: { isStack?: boolean; pane?: { id?: string; height?: number; minHeight?: number; order?: number } }
    ) => string | null
    overrideIndicator: (override: IndicatorCreate & { id?: string; paneId?: string }) => boolean
    removeIndicator: (filter?: { id?: string; name?: string; paneId?: string }) => boolean
    createOverlay: (overlay: string | OverlayCreate | Array<string | OverlayCreate>) => string | null | Array<string | null>
    overrideOverlay: (override: OverlayFilter & { visible?: boolean; lock?: boolean; styles?: Record<string, unknown> }) => boolean
    getOverlays: (filter?: OverlayFilter) => unknown[]
    removeOverlay: (filter?: OverlayFilter) => boolean
    setDataLoader: (loader: {
      getBars: (params: { callback: (data: KLineData[], more?: boolean) => void }) => void
      subscribeBar?: (params: { callback: (data: KLineData) => void }) => void
      unsubscribeBar?: () => void
    }) => void
    resetData: () => void
    resize: () => void
  }

  export function init(container: HTMLElement): Chart | null
  export function dispose(container: HTMLElement): void
  export function registerIndicator<D = unknown, C = unknown>(indicator: IndicatorTemplate<D, C>): void
  export function getSupportedIndicators(): string[]
  export function registerOverlay(overlay: OverlayTemplate): void
}

declare module '*.module.css' {
  const classes: Record<string, string>
  export default classes
}
