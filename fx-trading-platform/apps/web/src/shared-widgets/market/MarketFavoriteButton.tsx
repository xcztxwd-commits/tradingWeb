import { Star } from 'lucide-react'

import styles from './MarketFavoriteButton.module.css'

export function MarketFavoriteButton({
  active,
  symbol,
  onFavorite
}: {
  active: boolean
  symbol: string
  onFavorite: (symbol: string) => void
}) {
  return (
    <button
      type="button"
      className={styles.button}
      aria-label={active ? `取消自选 ${symbol}` : `添加自选 ${symbol}`}
      aria-pressed={active}
      onClick={(event) => {
        event.stopPropagation()
        onFavorite(symbol)
      }}
    >
      <Star size={15} aria-hidden="true" />
    </button>
  )
}
