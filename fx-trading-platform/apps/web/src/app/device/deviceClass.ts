export const mobileViewportQuery = '(max-width: 900px)'

export type DeviceClass = 'mobile' | 'pc'

type MediaQueryListener = () => void

export type DeviceClassMediaQuery = {
  readonly matches: boolean
  addEventListener(type: 'change', listener: MediaQueryListener): void
  removeEventListener(type: 'change', listener: MediaQueryListener): void
}

export type DeviceClassMatchMedia = (query: string) => DeviceClassMediaQuery

export type DeviceClassStore = {
  getSnapshot(): DeviceClass
  getServerSnapshot(): DeviceClass
  subscribe(listener: () => void): () => void
}

type DeviceClassStoreOptions = {
  serverDeviceClass?: DeviceClass
}

export function getDeviceClass(matchesMobile: boolean): DeviceClass {
  return matchesMobile ? 'mobile' : 'pc'
}

export function createDeviceClassStore(
  matchMedia: DeviceClassMatchMedia | undefined = getBrowserMatchMedia(),
  { serverDeviceClass = 'pc' }: DeviceClassStoreOptions = {}
): DeviceClassStore {
  const mediaQuery = matchMedia?.(mobileViewportQuery)
  const listeners = new Set<() => void>()
  const notify = () => {
    for (const listener of [...listeners]) listener()
  }

  return {
    getSnapshot: () => mediaQuery ? getDeviceClass(mediaQuery.matches) : serverDeviceClass,
    getServerSnapshot: () => serverDeviceClass,
    subscribe(listener) {
      if (!mediaQuery) return () => undefined
      if (listeners.size === 0) mediaQuery.addEventListener('change', notify)
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
        if (listeners.size === 0) mediaQuery.removeEventListener('change', notify)
      }
    }
  }
}

function getBrowserMatchMedia(): DeviceClassMatchMedia | undefined {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined
  return window.matchMedia.bind(window) as DeviceClassMatchMedia
}
