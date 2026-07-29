import { lazy } from 'react'

import { PlatformView } from '../../app/platform/PlatformView'
import type { TradingProduct } from '../../app/tradingRoutes'
import { TerminalSkeleton } from '../../components/loading/TerminalSkeleton'
import { LoginPromptDialog } from '../../shared-widgets/trading/components/LoginPromptDialog'
import { MarketSourceChangeNotice } from '../../shared-widgets/trading/components/MarketSourceChangeNotice'
import { useTradingRouteController } from './useTradingRouteController'
import styles from './TradingRoute.module.css'

const PcTradingTerminal = lazy(() =>
  import('../../pc/pages/trading/PcTradingTerminal').then((module) => ({ default: module.PcTradingTerminal }))
)
const MobileTradingTerminal = lazy(() =>
  import('../../mobile/pages/trading/MobileTradingTerminal').then((module) => ({ default: module.MobileTradingTerminal }))
)

export function TradingRoute({ product }: { product: TradingProduct }) {
  const controller = useTradingRouteController({ product })

  return (
    <div className={styles.page} data-controller-sentinel={controller.model.controllerSentinel}>
      <MarketSourceChangeNotice notice={controller.sourceNotice} />
      <PlatformView
        model={controller.model}
        pc={PcTradingTerminal}
        mobile={MobileTradingTerminal}
        fallback={<TerminalSkeleton />}
      />
      <LoginPromptDialog
        open={controller.loginPromptOpen}
        sessionMode={controller.model.tradePanelSessionMode}
        sessionError={controller.model.sessionError}
        onClose={controller.closeLoginPrompt}
        onLogin={controller.login}
        onRetry={() => void controller.model.onRetrySession()}
      />
    </div>
  )
}
