import { useCallback, useEffect, useState } from 'react'

import { readStoredAuthToken } from '../trading-session/tradingSessionStorage'
import {
  favoriteSymbolList,
  loadFavoriteSymbols,
  normalizeFavoriteSymbol,
  saveFavoriteSymbols,
  toggleFavoriteSymbol
} from './marketFavorites'
import { fetchMarketFavorites, setMarketFavorite } from './tradingMarketApi'

export function useMarketFavorites(token?: string | null) {
  const [storedToken] = useState(() => (token === undefined ? readStoredAuthToken() : null))
  const effectiveToken = token === undefined ? storedToken : token
  const [favorites, setFavorites] = useState<Set<string>>(() => loadFavoriteSymbols())

  useEffect(() => {
    saveFavoriteSymbols(favorites)
  }, [favorites])

  useEffect(() => {
    if (!effectiveToken) return
    let active = true
    void fetchMarketFavorites(effectiveToken)
      .then((remoteFavorites) => {
        if (!active) return
        setFavorites((current) => new Set(favoriteSymbolList([...current, ...remoteFavorites])))
      })
      .catch(() => undefined)
    return () => {
      active = false
    }
  }, [effectiveToken])

  const toggleFavorite = useCallback(
    (symbol: string) => {
      const normalizedSymbol = normalizeFavoriteSymbol(symbol)
      const nextFavorites = toggleFavoriteSymbol(favorites, normalizedSymbol)
      const nextFavorite = nextFavorites.has(normalizedSymbol)
      setFavorites(nextFavorites)
      if (effectiveToken) {
        void setMarketFavorite(normalizedSymbol, nextFavorite, effectiveToken)
          .then((remoteFavorites) => setFavorites(new Set(remoteFavorites)))
          .catch(() => undefined)
      }
    },
    [effectiveToken, favorites]
  )

  return { favorites, toggleFavorite }
}
