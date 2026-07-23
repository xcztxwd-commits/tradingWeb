import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  fetchBinanceFuturesDashboard,
  fetchBinanceMarketOverview,
  fetchMarketQuotes,
  fetchMarketSymbols,
  hydrateMarketFavorites,
  paginateRows,
  subscribeQuote,
  useMarketFavorites,
  type BinanceFuturesDashboard,
  type TradingMarket
} from '@fx-platform/frontend-core'

import { formatApiError, type ApiErrorView } from '../../shared-widgets/data/userPageModels'
import type {
  FuturesViewMode,
  MarketPageTab,
  MarketsRouteModel,
  MarketSortDirection,
  MarketSortKey,
  MarketUniverseTab,
  MarketZoneTab,
  TradingDataTab
} from './marketsRoute.types'
import {
  applyMarketQuote,
  applyRealtimeQuote,
  canHydrateMarketQuote,
  compareMarkets,
  filterMarketRows,
  mergeBinanceOverviewMarkets,
  mergeQuotedMarkets
} from './marketsRouteModel'
import { resolveMarketTradingTarget } from './marketTradingTarget'

const marketQuoteHydrationLimit = 40
const marketOverviewPageSize = 20

export function useMarketsRouteController(): MarketsRouteModel {
  const navigate = useNavigate()
  const [markets, setMarkets] = useState<TradingMarket[]>([])
  const [binanceOverview, setBinanceOverview] = useState<MarketsRouteModel['binanceOverview']>(null)
  const { favorites, toggleFavorite } = useMarketFavorites()
  const [query, setQuery] = useState('')
  const [pageTab, setPageTab] = useState<MarketPageTab>('trading-data')
  const [universeTab, setUniverseTabState] = useState<MarketUniverseTab>('crypto')
  const [zoneTab, setZoneTab] = useState<MarketZoneTab>('all')
  const [sortKey, setSortKey] = useState<MarketSortKey>('volume')
  const [sortDirection, setSortDirection] = useState<MarketSortDirection>('desc')
  const [marketPage, setMarketPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [apiError, setApiError] = useState<ApiErrorView | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [tradingDataTab, setTradingDataTab] = useState<TradingDataTab>('rankings')
  const [futuresViewMode, setFuturesViewMode] = useState<FuturesViewMode>('list')
  const [futuresPeriod, setFuturesPeriod] = useState<MarketsRouteModel['futures']['period']>('5m')
  const [futuresDashboard, setFuturesDashboard] = useState<BinanceFuturesDashboard | null>(null)
  const [futuresLoading, setFuturesLoading] = useState(false)
  const [futuresError, setFuturesError] = useState<string | null>(null)

  const hydratedMarkets = useMemo(() => hydrateMarketFavorites(markets, favorites), [favorites, markets])
  const visibleMarkets = useMemo(
    () => filterMarketRows(hydratedMarkets, query, universeTab, zoneTab)
      .sort((left, right) => compareMarkets(left, right, sortKey, sortDirection)),
    [hydratedMarkets, query, sortDirection, sortKey, universeTab, zoneTab]
  )
  const pagedMarkets = useMemo(
    () => paginateRows(visibleMarkets, marketPage, marketOverviewPageSize),
    [marketPage, visibleMarkets]
  )
  const visibleSymbolKey = useMemo(
    () => visibleMarkets
      .filter(canHydrateMarketQuote)
      .slice(0, marketQuoteHydrationLimit)
      .map((market) => market.symbol)
      .join('|'),
    [visibleMarkets]
  )

  useEffect(() => {
    let active = true
    setLoading(true)
    async function loadMarkets() {
      const [symbolsResult, overviewResult] = await Promise.allSettled([
        fetchMarketSymbols(),
        fetchBinanceMarketOverview()
      ])
      const symbols = symbolsResult.status === 'fulfilled' ? symbolsResult.value : []
      const nextOverview = overviewResult.status === 'fulfilled' ? overviewResult.value : null
      const nextMarkets = mergeBinanceOverviewMarkets(symbols, nextOverview?.markets ?? [])
      if (active) {
        setBinanceOverview(nextOverview)
        setMarkets(nextMarkets)
        setApiError(
          symbolsResult.status === 'rejected' && overviewResult.status === 'rejected'
            ? formatApiError(symbolsResult.reason)
            : null
        )
      }
      const quotedMarkets = await loadQuotedMarkets(
        nextMarkets.filter(canHydrateMarketQuote).slice(0, marketQuoteHydrationLimit)
      )
      if (active && quotedMarkets.length > 0) {
        setMarkets((current) => mergeQuotedMarkets(current, quotedMarkets))
      }
    }
    loadMarkets()
      .catch((error) => {
        if (!active) return
        setMarkets([])
        setBinanceOverview(null)
        setApiError(formatApiError(error))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [reloadKey])

  useEffect(() => {
    setMarketPage(1)
  }, [query, sortDirection, sortKey, universeTab, zoneTab])

  useEffect(() => {
    if (!visibleSymbolKey) return
    const unsubscribers = visibleSymbolKey.split('|').map((symbol) =>
      subscribeQuote(symbol, null, (quote) => {
        setMarkets((current) => current.map((market) =>
          market.symbol === quote.symbol ? applyRealtimeQuote(market, quote) : market
        ))
      })
    )
    return () => unsubscribers.forEach((unsubscribe) => unsubscribe())
  }, [visibleSymbolKey])

  useEffect(() => {
    if (pageTab !== 'trading-data') return
    let active = true
    setFuturesLoading(true)
    setFuturesError(null)
    fetchBinanceFuturesDashboard('BTCUSDT', futuresPeriod)
      .then((dashboard) => {
        if (active) setFuturesDashboard(dashboard)
      })
      .catch(() => {
        if (active) setFuturesError('Binance perpetual trading-data 暂时不可用')
      })
      .finally(() => {
        if (active) setFuturesLoading(false)
      })
    return () => {
      active = false
    }
  }, [futuresPeriod, pageTab])

  const selectSort = (nextKey: MarketSortKey) => {
    setSortKey(nextKey)
    setSortDirection(nextKey === 'symbol' ? 'asc' : 'desc')
  }
  const toggleTableSort = (nextKey: MarketSortKey) => {
    setSortKey((currentKey) => {
      if (currentKey !== nextKey) {
        setSortDirection(nextKey === 'symbol' ? 'asc' : 'desc')
        return nextKey
      }
      setSortDirection((current) => current === 'asc' ? 'desc' : 'asc')
      return currentKey
    })
  }
  const setUniverseTab = (nextTab: MarketUniverseTab) => {
    setUniverseTabState(nextTab)
    if (nextTab === 'forex') setZoneTab('all')
  }
  const openMarket = (market: TradingMarket) => {
    const target = resolveMarketTradingTarget(market)
    if (target) navigate(target)
  }

  return {
    markets,
    hydratedMarkets,
    visibleMarkets,
    pagedMarkets,
    binanceOverview,
    favorites,
    query,
    pageTab,
    universeTab,
    zoneTab,
    sortKey,
    sortDirection,
    loading,
    apiError,
    reloadKey,
    futures: {
      activeTab: tradingDataTab,
      viewMode: futuresViewMode,
      period: futuresPeriod,
      dashboard: futuresDashboard,
      loading: futuresLoading,
      error: futuresError,
      setActiveTab: setTradingDataTab,
      setViewMode: setFuturesViewMode,
      setPeriod: setFuturesPeriod
    },
    setQuery,
    setPageTab,
    setUniverseTab,
    setZoneTab,
    selectSort,
    toggleTableSort,
    changePage: (delta) => setMarketPage((current) => current + delta),
    toggleFavorite,
    openMarket,
    retry: () => {
      setApiError(null)
      setReloadKey((current) => current + 1)
    }
  }
}

async function loadQuotedMarkets(markets: TradingMarket[]) {
  if (markets.length === 0) return []
  try {
    const quotes = await fetchMarketQuotes(markets.map((market) => market.symbol))
    return markets.map((market) => quotes[market.symbol] ? applyMarketQuote(market, quotes[market.symbol]) : market)
  } catch {
    return markets
  }
}
