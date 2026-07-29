import { useState, useSyncExternalStore, type ReactNode } from 'react'

import {
  createTradingLabDesktopWidthSource,
  TRADING_LAB_NARROW_MESSAGE,
} from '../desktopWidth.ts'

export type TradingLabDesktopGuardProps = Readonly<{
  children: ReactNode
}>

export function TradingLabDesktopGuard({
  children,
}: TradingLabDesktopGuardProps) {
  const [desktopWidthSource] = useState(() =>
    createTradingLabDesktopWidthSource((query) => window.matchMedia(query)),
  )
  const isDesktop = useSyncExternalStore(
    desktopWidthSource.subscribe,
    desktopWidthSource.getSnapshot,
    () => false,
  )

  if (!isDesktop) {
    return (
      <div className="state-block" role="status">
        {TRADING_LAB_NARROW_MESSAGE}
      </div>
    )
  }

  return <>{children}</>
}
