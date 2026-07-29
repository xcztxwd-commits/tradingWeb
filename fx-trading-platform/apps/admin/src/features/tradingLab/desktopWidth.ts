export const TRADING_LAB_DESKTOP_MEDIA_QUERY = '(min-width: 1280px)'

export const TRADING_LAB_NARROW_MESSAGE =
  '交易路径实验室首版仅支持宽度不低于 1280px 的桌面端。'

type TradingLabMediaQueryChangeListener = (event: MediaQueryListEvent) => void

export interface TradingLabMediaQueryList {
  readonly matches: boolean
  addEventListener(
    type: 'change',
    listener: TradingLabMediaQueryChangeListener,
  ): void
  removeEventListener(
    type: 'change',
    listener: TradingLabMediaQueryChangeListener,
  ): void
}

export type TradingLabMatchMedia = (
  query: string,
) => TradingLabMediaQueryList

export interface TradingLabDesktopWidthSource {
  getSnapshot(): boolean
  subscribe(listener: () => void): () => void
}

export function createTradingLabDesktopWidthSource(
  matchMedia: TradingLabMatchMedia,
): TradingLabDesktopWidthSource {
  const mediaQueryList = matchMedia(TRADING_LAB_DESKTOP_MEDIA_QUERY)

  return {
    getSnapshot: () => mediaQueryList.matches,
    subscribe(listener) {
      const handleChange = (): void => {
        listener()
      }
      mediaQueryList.addEventListener('change', handleChange)

      return () => {
        mediaQueryList.removeEventListener('change', handleChange)
      }
    },
  }
}
