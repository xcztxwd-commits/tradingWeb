import type { CSSProperties } from 'react'

import styles from './AssetMark.module.css'
import { createAssetMarkModel, type CurrencyMark } from './assetMarkModel'

type AssetMarkProps = {
  symbol: string
  category?: string
  iconUrl?: string
  size?: 'sm' | 'md' | 'lg'
  className?: string
}

export function AssetMark({ symbol, category, iconUrl, size = 'md', className = '' }: AssetMarkProps) {
  const mark = createAssetMarkModel(symbol, category, iconUrl)
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
      {mark.imageUrl ? (
        <img
          className={styles.assetMark__image}
          src={mark.imageUrl}
          alt=""
          loading="lazy"
          referrerPolicy="no-referrer"
          onError={(event) => {
            event.currentTarget.hidden = true
          }}
        />
      ) : null}
      <span>{mark.display}</span>
    </span>
  )
}

function CurrencyIcon({ mark, side }: { mark: CurrencyMark; side: 'base' | 'quote' }) {
  const visual = mark.visual
  const classes = [
    styles.assetMark__currency,
    styles[`assetMark__currency--${side}`],
    mark.fallback ? styles['assetMark__currency--fallback'] : ''
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <span className={classes} title={`${mark.code} ${mark.label}`}>
      {visual.kind === 'flag' ? <FlagVisual visual={visual} /> : visual.text}
    </span>
  )
}

function FlagVisual({ visual }: { visual: Extract<CurrencyMark['visual'], { kind: 'flag' }> }) {
  const [first, second = first, third = second] = visual.colors
  const style = {
    '--asset-flag-1': first,
    '--asset-flag-2': second,
    '--asset-flag-3': third
  } as CSSProperties

  return <span className={styles.assetMark__flag} data-flag-style={visual.style} style={style} />
}
