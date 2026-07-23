import type { BinanceFuturesChartTone } from '@fx-platform/frontend-core'

const palette = {
  accent: '#fcd535',
  neutral: '#eaecef',
  buy: '#2ebd85',
  futuresBuy: '#0ecb81',
  sell: '#f6465d',
  drawingBlue: '#5ab6ff',
  ink: '#050505',
  volumeUp: '#16c784',
  volumeDown: '#ea3943',
  muted: '#8b93a1',
  noChange: '#999999'
} as const

const terminal = {
  dark: {
    grid: {
      show: true,
      horizontal: { color: '#2b3139', size: 1 },
      vertical: { color: '#252a32', size: 1 }
    },
    candle: {
      bar: {
        upColor: '#2ebd85',
        upBorderColor: '#2ebd85',
        upWickColor: '#2ebd85',
        downColor: '#f6465d',
        downBorderColor: '#f6465d',
        downWickColor: '#f6465d'
      },
      priceMark: {
        high: { color: '#848e9c' },
        low: { color: '#848e9c' },
        last: {
          upColor: '#2ebd85',
          downColor: '#f6465d',
          noChangeColor: '#fcd535'
        }
      }
    },
    xAxis: {
      axisLine: { color: '#2b3139' },
      tickText: { color: '#848e9c' }
    },
    yAxis: {
      axisLine: { color: '#2b3139' },
      tickText: { color: '#848e9c' }
    },
    separator: { color: '#2b3139', size: 1 },
    crosshair: {
      horizontal: {
        line: { color: '#5e6673' },
        text: { backgroundColor: '#2b3139', color: '#eaecef' }
      },
      vertical: {
        line: { color: '#5e6673' },
        text: { backgroundColor: '#2b3139', color: '#eaecef' }
      }
    },
    overlay: {
      point: {
        color: '#fcd535',
        borderColor: 'rgba(252, 213, 53, 0.36)',
        activeColor: '#fcd535',
        activeBorderColor: 'rgba(252, 213, 53, 0.44)'
      },
      line: { color: '#fcd535', size: 1, style: 'solid' },
      rect: {
        color: 'rgba(252, 213, 53, 0.14)',
        borderColor: '#fcd535',
        borderSize: 1
      },
      polygon: {
        color: 'rgba(252, 213, 53, 0.12)',
        borderColor: '#fcd535',
        borderSize: 1
      },
      text: {
        color: '#050505',
        backgroundColor: '#fcd535',
        borderColor: '#fcd535'
      }
    }
  },
  light: {
    grid: {
      show: true,
      horizontal: { color: '#e8edf4', size: 1 },
      vertical: { color: '#f1f4f8', size: 1 }
    },
    candle: {
      bar: {
        upColor: '#047857',
        upBorderColor: '#047857',
        upWickColor: '#047857',
        downColor: '#be123c',
        downBorderColor: '#be123c',
        downWickColor: '#be123c'
      },
      priceMark: {
        high: { color: '#475569' },
        low: { color: '#475569' },
        last: {
          upColor: '#047857',
          downColor: '#be123c',
          noChangeColor: '#111827'
        }
      }
    },
    xAxis: {
      axisLine: { color: '#dde4ee' },
      tickText: { color: '#667085' }
    },
    yAxis: {
      axisLine: { color: '#dde4ee' },
      tickText: { color: '#667085' }
    },
    separator: { color: '#dde4ee', size: 1 },
    crosshair: {
      horizontal: {
        line: { color: '#9aa4b2' },
        text: { backgroundColor: '#111827', color: '#ffffff' }
      },
      vertical: {
        line: { color: '#9aa4b2' },
        text: { backgroundColor: '#111827', color: '#ffffff' }
      }
    },
    overlay: {
      point: {
        color: '#111827',
        borderColor: 'rgba(17, 24, 39, 0.28)',
        activeColor: '#111827',
        activeBorderColor: 'rgba(17, 24, 39, 0.42)'
      },
      line: { color: '#111827', size: 1, style: 'solid' },
      rect: {
        color: 'rgba(17, 24, 39, 0.09)',
        borderColor: '#111827',
        borderSize: 1
      },
      polygon: {
        color: 'rgba(17, 24, 39, 0.08)',
        borderColor: '#111827',
        borderSize: 1
      },
      text: {
        color: '#ffffff',
        backgroundColor: '#111827',
        borderColor: '#111827'
      }
    }
  }
} as const

export const chartTheme = {
  palette,
  movingAverageColors: ['#ffab2e', '#e83e78', '#4dd0e1', '#f4511e', '#ab47bc', '#66bb6a'],
  genericIndicatorColors: ['#4dd0e1', '#ab47bc', '#66bb6a', '#ff7043'],
  area: {
    lineColor: '#f2b84b',
    backgroundColor: [
      { offset: 0, color: 'rgba(242, 184, 75, 0.02)' },
      { offset: 1, color: 'rgba(242, 184, 75, 0.16)' }
    ]
  },
  drawingOverlayColors: ['#fcd535', '#eaecef', '#2ebd85', '#f6465d', '#5ab6ff'],
  terminal,
  terminalImageBackground: {
    dark: '#050505',
    light: '#ffffff'
  },
  futures: {
    accent: '#fcd535',
    neutral: '#eaecef',
    buy: '#0ecb81',
    sell: '#f6465d',
    markerBackground: '#181a20'
  }
} as const

export function futuresChartColor(tone: BinanceFuturesChartTone) {
  return chartTheme.futures[tone]
}

export function chartColorWithAlpha(color: string, alpha: number) {
  if (!isHexColor(color)) return color
  return rgba(color, Math.max(0, Math.min(1, alpha)))
}

export function chartColorWithOpacity(color: string, opacity: number) {
  return chartColorWithAlpha(color, opacity / 100)
}

function rgba(color: string, alpha: number) {
  const value = color.slice(1)
  const red = Number.parseInt(value.slice(0, 2), 16)
  const green = Number.parseInt(value.slice(2, 4), 16)
  const blue = Number.parseInt(value.slice(4, 6), 16)
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`
}

function isHexColor(value: unknown): value is string {
  return typeof value === 'string' && /^#[\da-f]{6}$/i.test(value)
}
