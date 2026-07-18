import type { PerpetualTradingControlsModel } from '../../../shared-widgets/trading/usePerpetualTradingControls'
import { PerpetualTradingControls } from '../../../shared-widgets/trading/components/PerpetualTradingControls'
import { TradePanel } from '../../../shared-widgets/trading/order-form/TradePanel'
import type { TradePanelControllerModel } from '../../../shared-widgets/trading/order-form/useTradePanelController'
import { MobileOrderSheet } from './MobilePanels'

type Props = {
  open: boolean
  onClose: () => void
  tradePanel: TradePanelControllerModel
  perpetualControls: PerpetualTradingControlsModel
}

export function TradingOrderSheet({ open, onClose, tradePanel, perpetualControls }: Props) {
  return (
    <MobileOrderSheet open={open} title="Trade" onClose={onClose}>
      <PerpetualTradingControls controls={perpetualControls} />
      <TradePanel compact model={tradePanel} />
    </MobileOrderSheet>
  )
}
