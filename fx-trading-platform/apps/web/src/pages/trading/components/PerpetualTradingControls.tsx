import { SimplifiedLiquidationDisclaimer } from '../../../features/trading/components/SimplifiedLiquidationDisclaimer'
import { TradingSettingsBar } from '../../../features/trading/components/TradingSettingsBar'
import type { PerpetualTradingControlsModel } from '../usePerpetualTradingControls'
import { PerpetualReferenceStrip } from './PerpetualReferenceStrip'
import styles from '../../../features/trading/components/TradingControls.module.css'

export function PerpetualTradingControls({ controls }: { controls: PerpetualTradingControlsModel }) {
  if (!controls.visible) return null

  return (
    <section className={styles.perpetualControls} aria-label="Perpetual trading controls">
      {controls.reference ? <PerpetualReferenceStrip {...controls.reference} /> : (
        <p className={styles.referenceUnavailable} role="status">Perpetual reference data unavailable.</p>
      )}
      <TradingSettingsBar
        positionMode={controls.positionMode}
        marginMode={controls.marginMode}
        leverage={controls.leverage}
        maxLeverage={controls.maxLeverage}
        quantityUnit={controls.quantityUnit}
        disabled={controls.disabled}
        pending={controls.pending}
        error={controls.error}
        onPositionModeChange={controls.onPositionModeChange}
        onMarginModeChange={controls.onMarginModeChange}
        onLeverageChange={controls.onLeverageChange}
        onQuantityUnitChange={controls.onQuantityUnitChange}
      />
      <SimplifiedLiquidationDisclaimer />
    </section>
  )
}
