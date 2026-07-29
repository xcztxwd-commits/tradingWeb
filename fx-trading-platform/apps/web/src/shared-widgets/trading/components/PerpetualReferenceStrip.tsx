import { MarketSourceBadge } from '../order-form/MarketSourceBadge'
import type { MarketSource } from '../order-form/MarketSourceBadge'
import styles from './PerpetualReferenceStrip.module.css'

type Props = {
  markPrice: string
  indexPrice: string
  fundingRate: string
  fundingCountdown: string
  marketSource: MarketSource
  providerCode?: string | null
  stale?: boolean
}

export function PerpetualReferenceStrip({
  markPrice,
  indexPrice,
  fundingRate,
  fundingCountdown,
  marketSource,
  providerCode,
  stale = false
}: Props) {
  return (
    <section className={styles.strip} aria-label="Perpetual reference data">
      <dl className={styles.metrics}>
        <div>
          <dt>Mark price</dt>
          <dd>{markPrice}</dd>
        </div>
        <div>
          <dt>Index price</dt>
          <dd>{indexPrice}</dd>
        </div>
        <div>
          <dt>Funding rate</dt>
          <dd>{fundingRate}</dd>
        </div>
        <div>
          <dt>Next funding</dt>
          <dd>{fundingCountdown}</dd>
        </div>
      </dl>
      <MarketSourceBadge source={marketSource} providerCode={providerCode} stale={stale} />
    </section>
  )
}
