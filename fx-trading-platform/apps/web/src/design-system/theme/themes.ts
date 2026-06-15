export type TradingThemeId =
  | 'midnight-pro'
  | 'binance-inspired'
  | 'okx-inspired'
  | 'deep-blue-quant'
  | 'light-institutional'

export type TradingThemeTokens = {
  background: string
  surface: string
  surfaceElevated: string
  border: string
  textPrimary: string
  textSecondary: string
  textMuted: string
  primary: string
  primaryHover: string
  accent: string
  success: string
  buy: string
  danger: string
  sell: string
  warning: string
  chartGrid: string
  chartCandleUp: string
  chartCandleDown: string
  orderBookBidBg: string
  orderBookAskBg: string
}

export type TradingTheme = {
  id: TradingThemeId
  name: string
  description: string
  colorScheme: 'dark' | 'light'
  tokens: TradingThemeTokens
}

export const defaultThemeId = 'binance-inspired'

export const tradingThemes: TradingTheme[] = [
  {
    id: 'midnight-pro',
    name: 'Midnight Pro',
    description: 'settings.themeDescriptions.midnightPro',
    colorScheme: 'dark',
    tokens: {
      background: '#05080C',
      surface: '#0D1219',
      surfaceElevated: '#151D28',
      border: '#1D2530',
      textPrimary: '#F6F8FB',
      textSecondary: '#DCE3EC',
      textMuted: '#8A94A3',
      primary: '#F2B84B',
      primaryHover: '#FFD166',
      accent: '#4FB6FF',
      success: '#20B26B',
      buy: '#20B26B',
      danger: '#F05267',
      sell: '#F05267',
      warning: '#F2B84B',
      chartGrid: '#151D28',
      chartCandleUp: '#26A69A',
      chartCandleDown: '#EF5350',
      orderBookBidBg: 'rgba(23, 154, 91, 0.25)',
      orderBookAskBg: 'rgba(201, 47, 65, 0.27)'
    }
  },
  {
    id: 'binance-inspired',
    name: 'Binance Inspired',
    description: 'settings.themeDescriptions.binanceInspired',
    colorScheme: 'dark',
    tokens: {
      background: '#181a20',
      surface: '#202630',
      surfaceElevated: '#29313d',
      border: '#333b47',
      textPrimary: '#eaecef',
      textSecondary: '#929aa5',
      textMuted: '#707a8a',
      primary: '#f0b90b',
      primaryHover: '#fcd535',
      accent: '#fcd535',
      success: '#2ebd85',
      buy: '#2ebd85',
      danger: '#f6465d',
      sell: '#f6465d',
      warning: '#f0b90b',
      chartGrid: '#333b47',
      chartCandleUp: '#2ebd85',
      chartCandleDown: '#f6465d',
      orderBookBidBg: 'rgba(46, 189, 133, 0.1)',
      orderBookAskBg: 'rgba(246, 70, 93, 0.1)'
    }
  },
  {
    id: 'okx-inspired',
    name: 'OKX Inspired',
    description: 'settings.themeDescriptions.okxInspired',
    colorScheme: 'dark',
    tokens: {
      background: '#030405',
      surface: '#0E1012',
      surfaceElevated: '#181B1F',
      border: '#282C32',
      textPrimary: '#F5F5F5',
      textSecondary: '#D6D8DC',
      textMuted: '#8D9299',
      primary: '#F4F4F5',
      primaryHover: '#FFFFFF',
      accent: '#A1A7B0',
      success: '#17A76F',
      buy: '#17A76F',
      danger: '#E3505B',
      sell: '#E3505B',
      warning: '#D6A84F',
      chartGrid: '#171A1F',
      chartCandleUp: '#20A77A',
      chartCandleDown: '#E0525E',
      orderBookBidBg: 'rgba(23, 167, 111, 0.2)',
      orderBookAskBg: 'rgba(227, 80, 91, 0.22)'
    }
  },
  {
    id: 'deep-blue-quant',
    name: 'Deep Blue Quant',
    description: 'settings.themeDescriptions.deepBlueQuant',
    colorScheme: 'dark',
    tokens: {
      background: '#050914',
      surface: '#0B1220',
      surfaceElevated: '#101A2B',
      border: '#1D2B42',
      textPrimary: '#F1F7FF',
      textSecondary: '#D6E2F1',
      textMuted: '#8B9CB3',
      primary: '#4FB6FF',
      primaryHover: '#79C8FF',
      accent: '#78E1F7',
      success: '#22C48B',
      buy: '#22C48B',
      danger: '#F26473',
      sell: '#F26473',
      warning: '#F3B95F',
      chartGrid: '#142033',
      chartCandleUp: '#2AC39A',
      chartCandleDown: '#F05F70',
      orderBookBidBg: 'rgba(34, 196, 139, 0.21)',
      orderBookAskBg: 'rgba(242, 100, 115, 0.23)'
    }
  },
  {
    id: 'light-institutional',
    name: 'Light Institutional',
    description: 'settings.themeDescriptions.lightInstitutional',
    colorScheme: 'light',
    tokens: {
      background: '#F3F6FA',
      surface: '#FFFFFF',
      surfaceElevated: '#EEF3F8',
      border: '#D8DEE8',
      textPrimary: '#0F172A',
      textSecondary: '#1F2937',
      textMuted: '#657386',
      primary: '#111827',
      primaryHover: '#273244',
      accent: '#2563EB',
      success: '#059669',
      buy: '#059669',
      danger: '#DC3F5F',
      sell: '#DC3F5F',
      warning: '#B7791F',
      chartGrid: '#E6EBF2',
      chartCandleUp: '#059669',
      chartCandleDown: '#DC3F5F',
      orderBookBidBg: 'rgba(5, 150, 105, 0.16)',
      orderBookAskBg: 'rgba(220, 63, 95, 0.18)'
    }
  }
]

export function isTradingThemeId(value: unknown): value is TradingThemeId {
  return typeof value === 'string' && tradingThemes.some((theme) => theme.id === value)
}

export function getTradingTheme(themeId: TradingThemeId): TradingTheme {
  return tradingThemes.find((theme) => theme.id === themeId) ?? tradingThemes[0]
}
