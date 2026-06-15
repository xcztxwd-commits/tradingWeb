export type TradingThemeId = 'binance-inspired' | 'minimal-white'

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
  colorScheme: 'dark' | 'light'
  tokens: TradingThemeTokens
}

export const defaultThemeId: TradingThemeId = 'binance-inspired'

export const tradingThemes: TradingTheme[] = [
  {
    id: 'binance-inspired',
    name: 'Binance Inspired',
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
    id: 'minimal-white',
    name: 'Minimal White',
    colorScheme: 'light',
    tokens: {
      background: '#f6f8fb',
      surface: '#ffffff',
      surfaceElevated: '#f9fafc',
      border: '#e2e7ef',
      textPrimary: '#111827',
      textSecondary: '#475569',
      textMuted: '#475569',
      primary: '#111827',
      primaryHover: '#0f172a',
      accent: '#111827',
      success: '#047857',
      buy: '#047857',
      danger: '#be123c',
      sell: '#be123c',
      warning: '#9a6a00',
      chartGrid: '#e8edf4',
      chartCandleUp: '#047857',
      chartCandleDown: '#be123c',
      orderBookBidBg: 'rgba(7, 135, 90, 0.08)',
      orderBookAskBg: 'rgba(212, 61, 86, 0.08)'
    }
  }
]

export function getTradingTheme(themeId: TradingThemeId = defaultThemeId): TradingTheme {
  return tradingThemes.find((theme) => theme.id === themeId) ?? tradingThemes[0]
}
