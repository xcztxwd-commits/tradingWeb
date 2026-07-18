import { TradePanel } from '../../../features/trading/components/TradePanel'
import type { TradingTerminalViewProps } from '../../../routes/trading/tradingRoute.types'
import { MobileOrderSheet } from './MobilePanels'
import { PerpetualTradingControls } from './PerpetualTradingControls'

type Props = {
  open: boolean
  onClose: () => void
  view: TradingTerminalViewProps
}

export function TradingOrderSheet({ open, onClose, view }: Props) {
  return (
    <MobileOrderSheet open={open} title="Trade" onClose={onClose}>
      <PerpetualTradingControls controls={view.perpetualControls} />
      <TradePanel
        compact
        accountId={view.accountId}
        adapterSettings={view.perpetualControls.adapterSettings}
        settingsReady={view.perpetualControls.ready}
        balances={view.balances}
        category={view.market.category}
        productType={view.market.productType}
        sessionReady={view.sessionReady}
        sessionMode={view.tradePanelSessionMode}
        sessionError={view.sessionError}
        loginRequired={view.loginRequired}
        leverage={view.market.leverage}
        rules={view.market.rules}
        minOrderAmount={view.tradeMinOrderAmount}
        pricePrecision={view.tradePricePrecision}
        quantityPrecision={view.tradeQuantityPrecision}
        pricePrefill={view.tradePricePrefill}
        symbol={view.symbol}
        onLoginRequired={view.onLoginRequired}
        onSubmitOrder={view.submitOrder}
        onSubmitOco={view.submitOco}
        onRetrySession={view.onRetrySession}
      />
    </MobileOrderSheet>
  )
}
