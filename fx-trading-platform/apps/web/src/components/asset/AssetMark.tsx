import styles from './AssetMark.module.css'
import { createAssetMarkModel, type CurrencyMark } from './assetMarkModel'

type AssetMarkProps = {
  symbol: string
  category?: string
  size?: 'sm' | 'md' | 'lg'
  className?: string
}

export function AssetMark({ symbol, category, size = 'md', className = '' }: AssetMarkProps) {
  const mark = createAssetMarkModel(symbol, category)
  const classes = [
    styles.assetMark,
    styles[`assetMark--${size}`],
    styles[`assetMark--${mark.variant}`] ?? styles['assetMark--default'],
    mark.kind === 'pair' ? styles['assetMark--pair'] : '',
    className
  ]
    .filter(Boolean)
    .join(' ')

  if (mark.kind === 'pair') {
    return (
      <span className={classes} aria-hidden="true" title={mark.label} data-asset={mark.asset} data-asset-kind="pair">
        <span className={styles.assetMark__pair}>
          <CurrencyIcon mark={mark.base} side="base" />
          <CurrencyIcon mark={mark.quote} side="quote" />
        </span>
      </span>
    )
  }

  return (
    <span className={classes} aria-hidden="true" title={mark.label} data-asset={mark.asset} data-asset-kind="single">
      <span>{mark.display}</span>
    </span>
  )
}

function CurrencyIcon({ mark, side }: { mark: CurrencyMark; side: 'base' | 'quote' }) {
  const classes = [
    styles.assetMark__currency,
    styles[`assetMark__currency--${side}`],
    mark.fallback ? styles['assetMark__currency--fallback'] : ''
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <span className={classes} title={`${mark.code} ${mark.label}`}>
      {mark.icon}
    </span>
  )
}
