import { registerOverlay } from 'klinecharts'

type Coordinate = { x: number; y: number }
type Bounding = { width: number; height: number }
type OverlayCreateParams = {
  coordinates: Coordinate[]
  bounding: Bounding
}
type OverlayFigure = {
  type: string
  attrs: unknown
  isCheckEvent?: boolean
}
type OverlayTemplate = {
  name: string
  totalStep: number
  needDefaultPointFigure: boolean
  needDefaultXAxisFigure: boolean
  needDefaultYAxisFigure: boolean
  createPointFigures: (params: OverlayCreateParams) => OverlayFigure[]
}

let registered = false

export function registerTradingDrawingOverlays() {
  if (registered) return
  registered = true
  tradingDrawingOverlays.forEach((overlay) => registerOverlay(overlay))
}

const tradingDrawingOverlays: OverlayTemplate[] = [
  {
    name: 'tradingArrowLine',
    totalStep: 3,
    needDefaultPointFigure: true,
    needDefaultXAxisFigure: true,
    needDefaultYAxisFigure: true,
    createPointFigures: ({ coordinates }) => {
      if (coordinates.length < 2) return []
      return [
        {
          type: 'line',
          attrs: { coordinates }
        },
        {
          type: 'polygon',
          attrs: { coordinates: createArrowHead(coordinates[0], coordinates[1]) }
        }
      ]
    }
  },
  {
    name: 'tradingRectangle',
    totalStep: 3,
    needDefaultPointFigure: true,
    needDefaultXAxisFigure: true,
    needDefaultYAxisFigure: true,
    createPointFigures: ({ coordinates }) => {
      if (coordinates.length < 2) return []
      return [
        {
          type: 'rect',
          attrs: createRectAttrs(coordinates[0], coordinates[1])
        }
      ]
    }
  },
  {
    name: 'tradingCircle',
    totalStep: 3,
    needDefaultPointFigure: true,
    needDefaultXAxisFigure: true,
    needDefaultYAxisFigure: true,
    createPointFigures: ({ coordinates }) => {
      if (coordinates.length < 2) return []
      return [
        {
          type: 'circle',
          attrs: {
            x: coordinates[0].x,
            y: coordinates[0].y,
            r: Math.max(1, getDistance(coordinates[0], coordinates[1]))
          }
        }
      ]
    }
  },
  {
    name: 'tradingTriangle',
    totalStep: 3,
    needDefaultPointFigure: true,
    needDefaultXAxisFigure: true,
    needDefaultYAxisFigure: true,
    createPointFigures: ({ coordinates }) => {
      if (coordinates.length < 2) return []
      const start = coordinates[0]
      const end = coordinates[1]
      return [
        {
          type: 'polygon',
          attrs: {
            coordinates: [
              { x: (start.x + end.x) / 2, y: start.y },
              { x: end.x, y: end.y },
              { x: start.x, y: end.y }
            ]
          }
        }
      ]
    }
  },
  {
    name: 'tradingParallelRayLine',
    totalStep: 4,
    needDefaultPointFigure: true,
    needDefaultXAxisFigure: true,
    needDefaultYAxisFigure: true,
    createPointFigures: ({ coordinates, bounding }) => {
      if (coordinates.length < 2) return []
      const lines = [createRayLine(coordinates[0], coordinates[1], bounding)]
      if (coordinates.length > 2) {
        lines.push(
          createRayLine(
            coordinates[2],
            {
              x: coordinates[2].x + coordinates[1].x - coordinates[0].x,
              y: coordinates[2].y + coordinates[1].y - coordinates[0].y
            },
            bounding
          )
        )
      }
      return [{ type: 'line', attrs: lines }]
    }
  },
  {
    name: 'tradingFibonacciExtension',
    totalStep: 4,
    needDefaultPointFigure: true,
    needDefaultXAxisFigure: true,
    needDefaultYAxisFigure: true,
    createPointFigures: ({ coordinates, bounding }) => {
      if (coordinates.length < 2) return []
      const base = coordinates[1]
      const anchor = coordinates[2] ?? coordinates[1]
      const deltaY = coordinates[0].y - base.y
      const ratios = [0, 0.618, 1, 1.618, 2.618]
      return createHorizontalRatioFigures(anchor.y, deltaY, ratios, bounding)
    }
  },
  {
    name: 'tradingFibonacciFan',
    totalStep: 3,
    needDefaultPointFigure: true,
    needDefaultXAxisFigure: true,
    needDefaultYAxisFigure: true,
    createPointFigures: ({ coordinates, bounding }) => {
      if (coordinates.length < 2) return []
      const start = coordinates[0]
      const end = coordinates[1]
      const endX = end.x >= start.x ? bounding.width : 0
      const dx = end.x - start.x || 1
      const ratios = [0.382, 0.5, 0.618]
      const lines = ratios.map((ratio) => {
        const targetY = start.y + (end.y - start.y) * ratio
        const slope = (targetY - start.y) / dx
        return {
          coordinates: [
            start,
            {
              x: endX,
              y: start.y + (endX - start.x) * slope
            }
          ]
        }
      })
      const texts = ratios.map((ratio, index) => ({
        x: Math.min(Math.max(start.x + 6, 0), bounding.width - 52),
        y: lines[index].coordinates[1].y,
        text: `${(ratio * 100).toFixed(1)}%`,
        baseline: 'bottom'
      }))
      return [
        { type: 'line', attrs: lines },
        { type: 'text', isCheckEvent: false, attrs: texts }
      ]
    }
  }
]

function createRectAttrs(start: Coordinate, end: Coordinate) {
  return {
    x: start.x,
    y: start.y,
    width: end.x - start.x,
    height: end.y - start.y
  }
}

function createArrowHead(start: Coordinate, end: Coordinate): Coordinate[] {
  const angle = Math.atan2(end.y - start.y, end.x - start.x)
  const length = 11
  const spread = Math.PI / 7
  return [
    end,
    {
      x: end.x - Math.cos(angle - spread) * length,
      y: end.y - Math.sin(angle - spread) * length
    },
    {
      x: end.x - Math.cos(angle + spread) * length,
      y: end.y - Math.sin(angle + spread) * length
    }
  ]
}

function createRayLine(start: Coordinate, through: Coordinate, bounding: Bounding) {
  if (start.x === through.x) {
    return {
      coordinates: [
        start,
        {
          x: start.x,
          y: through.y >= start.y ? bounding.height : 0
        }
      ]
    }
  }

  const endX = through.x >= start.x ? bounding.width : 0
  return {
    coordinates: [
      start,
      {
        x: endX,
        y: start.y + ((through.y - start.y) / (through.x - start.x)) * (endX - start.x)
      }
    ]
  }
}

function createHorizontalRatioFigures(anchorY: number, deltaY: number, ratios: number[], bounding: Bounding) {
  const lines = ratios.map((ratio) => {
    const y = anchorY + deltaY * ratio
    return { coordinates: [{ x: 0, y }, { x: bounding.width, y }] }
  })
  const texts = ratios.map((ratio, index) => ({
    x: 0,
    y: lines[index].coordinates[0].y,
    text: `${(ratio * 100).toFixed(1)}%`,
    baseline: 'bottom'
  }))
  return [
    { type: 'line', attrs: lines },
    { type: 'text', isCheckEvent: false, attrs: texts }
  ]
}

function getDistance(start: Coordinate, end: Coordinate) {
  return Math.hypot(end.x - start.x, end.y - start.y)
}
