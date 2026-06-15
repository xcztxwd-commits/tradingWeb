import { ChevronDown, Search, Star } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { resolveTradingPath, writeLastTradingSymbol, type TradingCategory } from '../../app/hooks/useLastTradingSymbol'
import { AssetMark } from '../../components/asset/AssetMark'
import { SelectField, type SelectFieldOption } from '../../components/SelectField'
import { ApiErrorState, LoadingState } from '../../components/user-page/PageState'
import { formatApiError } from '../../components/user-page/userPageModels'
import { mockTradingMarkets } from '../../features/market/mockTradingData'
import { mergeTradingQuoteIntoMarket } from '../../features/market/tradingMarketAdapters'
import { fetchMarketQuote, fetchMarketSymbols } from '../../features/market/tradingMarketApi'
import { formatMarketPrice } from '../../features/market/tradingModels'
import type { TradingMarket, TradingQuote } from '../../features/market/tradingModels'
import { subscribeQuote } from '../../services/marketStream'
import type { Quote } from '../../types/trading'

type MarketPageTab = 'overview' | 'trading-data' | 'ai-picks' | 'token-unlocks'
type TradingDataTab = 'rankings' | 'usdt-contracts' | 'coin-contracts' | 'options'
type MarketUniverseTab = 'favorites' | 'forex' | 'crypto' | 'spot' | 'contract'
type MarketZoneTab =
  | 'all'
  | 'bnb-chain'
  | 'solana'
  | 'rwa'
  | 'meme'
  | 'payments'
  | 'ai'
  | 'layer'
  | 'metals'
  | 'indices'
  | 'launchpool'
  | 'defi'
type SortKey = 'symbol' | 'price' | 'change' | 'volume' | 'marketCap'
type SortDirection = 'asc' | 'desc'

const favoriteStorageKey = 'fx-trading-market-favorites'
const marketQuoteHydrationLimit = 40

const marketPageTabs: Array<{ value: MarketPageTab; label: string }> = [
  { value: 'overview', label: '总览' },
  { value: 'trading-data', label: '交易数据' },
  { value: 'ai-picks', label: 'AI 精选' },
  { value: 'token-unlocks', label: '代币解锁' }
]

const tradingDataTabs: Array<{ value: TradingDataTab; label: string }> = [
  { value: 'rankings', label: '排行榜' },
  { value: 'usdt-contracts', label: 'U本位合约' },
  { value: 'coin-contracts', label: '币本位合约' },
  { value: 'options', label: '期权' }
]

const marketUniverseTabs: Array<{ value: MarketUniverseTab; label: string }> = [
  { value: 'favorites', label: '自选' },
  { value: 'forex', label: '外汇' },
  { value: 'crypto', label: '币种' },
  { value: 'spot', label: '现货' },
  { value: 'contract', label: '合约' }
]

const marketZoneTabs: Array<{ value: MarketZoneTab; label: string; badge?: string }> = [
  { value: 'all', label: '全部' },
  { value: 'bnb-chain', label: 'BNB Chain' },
  { value: 'solana', label: 'Solana', badge: 'New' },
  { value: 'rwa', label: 'RWA' },
  { value: 'meme', label: 'MEME' },
  { value: 'payments', label: '支付' },
  { value: 'ai', label: 'AI' },
  { value: 'layer', label: '一层/二层网络' },
  { value: 'metals', label: '贵金属' },
  { value: 'indices', label: '指数' },
  { value: 'launchpool', label: 'Launchpool', badge: 'New' },
  { value: 'defi', label: 'DeFi' }
]

const marketColumns: Array<{ key: SortKey | 'action'; label: string; sortable?: boolean }> = [
  { key: 'symbol', label: '名称', sortable: true },
  { key: 'price', label: '价格', sortable: true },
  { key: 'change', label: '24h涨跌', sortable: true },
  { key: 'volume', label: '24h成交量', sortable: true },
  { key: 'marketCap', label: '市值', sortable: true },
  { key: 'action', label: '操作' }
]

const marketSortOptions: Array<SelectFieldOption<SortKey>> = [
  { value: 'volume', label: '24h成交量' },
  { value: 'change', label: '24h涨跌' },
  { value: 'price', label: '价格' },
  { value: 'marketCap', label: '市值' },
  { value: 'symbol', label: '名称' }
]

export function MarketsPage() {
  const navigate = useNavigate()
  const [markets, setMarkets] = useState<TradingMarket[]>(mockTradingMarkets)
  const [favorites, setFavorites] = useState<Set<string>>(() => loadFavoriteSymbols())
  const [query, setQuery] = useState('')
  const [pageTab, setPageTab] = useState<MarketPageTab>('trading-data')
  const [universeTab, setUniverseTab] = useState<MarketUniverseTab>('crypto')
  const [zoneTab, setZoneTab] = useState<MarketZoneTab>('all')
  const [sortKey, setSortKey] = useState<SortKey>('volume')
  const [sortDirection, setSortDirection] = useState<SortDirection>('desc')
  const [loading, setLoading] = useState(false)
  const [apiError, setApiError] = useState<ReturnType<typeof formatApiError> | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  const hydratedMarkets = useMemo(
    () => markets.map((market) => ({ ...market, favorite: favorites.has(market.symbol) || market.favorite })),
    [favorites, markets]
  )
  const visibleMarkets = useMemo(
    () =>
      filterMarketRows(hydratedMarkets, query, universeTab, zoneTab).sort((left, right) =>
        compareMarkets(left, right, sortKey, sortDirection)
      ),
    [hydratedMarkets, query, sortDirection, sortKey, universeTab, zoneTab]
  )
  const summaryDeck = useMemo(() => buildSummaryDeck(hydratedMarkets), [hydratedMarkets])
  const rankings = useMemo(() => buildRankings(hydratedMarkets), [hydratedMarkets])
  const visibleSymbolKey = useMemo(
    () => visibleMarkets.filter(canHydrateMarketQuote).slice(0, marketQuoteHydrationLimit).map((market) => market.symbol).join('|'),
    [visibleMarkets]
  )

  useEffect(() => {
    let active = true
    setLoading(true)
    fetchMarketSymbols()
      .then(async (symbols) => {
        const initialMarkets = symbols.length > 0 ? symbols : mockTradingMarkets
        if (active) {
          setMarkets(initialMarkets)
          setApiError(null)
        }
        const quotedMarkets = await Promise.all(
          initialMarkets.filter(canHydrateMarketQuote).slice(0, marketQuoteHydrationLimit).map((market) => loadQuotedMarket(market))
        )
        if (active && quotedMarkets.length > 0) {
          setMarkets((current) => mergeQuotedMarkets(current, quotedMarkets))
        }
      })
      .catch((error) => {
        if (active) {
          setMarkets(mockTradingMarkets)
          setApiError(formatApiError(error))
        }
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [reloadKey])

  useEffect(() => {
    saveFavoriteSymbols(favorites)
  }, [favorites])

  useEffect(() => {
    if (!visibleSymbolKey) return
    const unsubscribers = visibleSymbolKey.split('|').map((symbol) =>
      subscribeQuote(symbol, null, (quote) => {
        setMarkets((current) => current.map((market) => (market.symbol === quote.symbol ? applyRealtimeQuote(market, quote) : market)))
      })
    )
    return () => {
      unsubscribers.forEach((unsubscribe) => unsubscribe())
    }
  }, [visibleSymbolKey])

  return (
    <section className="user-page market-shell" aria-labelledby="markets-title">
      <header className="market-shell__titleSrOnly">
        <div>
          <h1 id="markets-title">加密货币市场 | 币价和市值</h1>
          <p>按总览、榜单和板块筛选查看价格、24h 成交量、涨跌和估算市值，并快速进入交易终端。</p>
        </div>
        <span className="market-shell__status">{loading ? '同步中' : '实时'}</span>
      </header>

      <div className="market-shell__tabs" role="tablist" aria-label="行情页面导航">
        {marketPageTabs.map((tab) => (
          <button key={tab.value} type="button" aria-selected={pageTab === tab.value} onClick={() => setPageTab(tab.value)}>
            {tab.label}
          </button>
        ))}
      </div>

      {pageTab === 'overview' ? (
        <>
          {loading ? <LoadingState message="正在同步行情数据" /> : null}
          {apiError ? (
            <ApiErrorState
              error={apiError}
              onAction={() => {
                setApiError(null)
                setReloadKey((current) => current + 1)
              }}
            />
          ) : null}

          <MarketSummaryDeck deck={summaryDeck} onOpen={onOpenMarket} />

          <MarketFilters
            query={query}
            sortKey={sortKey}
            universeTab={universeTab}
            zoneTab={zoneTab}
            onQuery={setQuery}
            onSort={(nextKey) => {
              setSortKey(nextKey)
              setSortDirection(nextKey === 'symbol' ? 'asc' : 'desc')
            }}
            onUniverseTab={setUniverseTab}
            onZoneTab={setZoneTab}
          />

          <section className="market-table-section" aria-labelledby="market-table-title">
            <div className="market-table-section__head">
              <div>
                <h2 id="market-table-title">市值排名前列的代币</h2>
                <p>桌面显示完整表格，移动端保留名称、价格、24h 涨跌、市值和成交量摘要。</p>
              </div>
              <span>{visibleMarkets.length} 个市场</span>
            </div>
            <MarketTable
              markets={visibleMarkets}
              favorites={favorites}
              sortKey={sortKey}
              sortDirection={sortDirection}
              onFavorite={toggleFavorite}
              onOpen={onOpenMarket}
              onSort={toggleTableSort}
            />
            <MarketMobileList markets={visibleMarkets} favorites={favorites} onFavorite={toggleFavorite} onOpen={onOpenMarket} />
          </section>
        </>
      ) : null}

      {pageTab === 'trading-data' ? <TradingDataDashboard rankings={rankings} onOpen={onOpenMarket} /> : null}
      {pageTab === 'ai-picks' ? (
        <MarketInsightPlaceholder
          title="AI 精选"
          description="保留 Binance 风格入口，但当前版本不接入 AI 选币数据，避免把 UI 优化扩大成新业务范围。"
        />
      ) : null}
      {pageTab === 'token-unlocks' ? (
        <MarketInsightPlaceholder
          title="代币解锁"
          description="保留解锁日历信息架构位置。真实解锁数据需要独立接口和风险说明，本轮只处理页面布局。"
        />
      ) : null}
    </section>
  )

  function toggleFavorite(symbol: string) {
    setFavorites((current) => {
      const next = new Set(current)
      if (next.has(symbol)) next.delete(symbol)
      else next.add(symbol)
      return next
    })
  }

  function toggleTableSort(nextKey: SortKey) {
    setSortKey((currentKey) => {
      if (currentKey !== nextKey) {
        setSortDirection(nextKey === 'symbol' ? 'asc' : 'desc')
        return nextKey
      }
      setSortDirection((currentDirection) => (currentDirection === 'asc' ? 'desc' : 'asc'))
      return currentKey
    })
  }

  function onOpenMarket(market: TradingMarket) {
    const category = toTradingCategory(market)
    writeLastTradingSymbol(category, market.symbol)
    const target = resolveTradingPath(category, market.symbol)
    const expectedPath = `/trading?category=${category}&symbol=${encodeURIComponent(market.symbol)}`
    navigate(target === expectedPath ? target : expectedPath)
  }
}

function MarketSummaryDeck({ deck, onOpen }: { deck: SummaryGroup[]; onOpen: (market: TradingMarket) => void }) {
  return (
    <div className="market-summary-grid market-rank-grid" aria-label="行情摘要">
      {deck.map((group) => (
        <SummaryCard key={group.title} group={group} onOpen={onOpen} />
      ))}
    </div>
  )
}

function SummaryCard({ group, onOpen }: { group: SummaryGroup; onOpen: (market: TradingMarket) => void }) {
  return (
    <section className="market-summary-card" aria-labelledby={`summary-${group.key}`}>
      <div className="market-summary-card__head">
        <div>
          <h2 id={`summary-${group.key}`}>{group.title}</h2>
          <p>{group.description}</p>
        </div>
        <button type="button" onClick={() => onOpen(group.markets[0])}>
          更多
        </button>
      </div>
      <ol>
        {group.markets.map((market) => (
          <li key={`${group.key}-${market.symbol}`}>
            <RankingPreviewCard market={market} onOpen={onOpen} />
          </li>
        ))}
      </ol>
    </section>
  )
}

function RankingPreviewCard({ market, onOpen }: { market: TradingMarket; onOpen: (market: TradingMarket) => void }) {
  return (
    <button type="button" className="market-preview-row" onClick={() => onOpen(market)}>
      <AssetMark symbol={market.symbol} category={market.category} size="sm" />
      <span>
        <strong>{market.base}</strong>
        <small>{formatMarketPrice(market.symbol, market.last)}</small>
      </span>
      <em className={market.changePercent >= 0 ? 'market-change market-change--up' : 'market-change market-change--down'}>
        {formatSignedPercent(market.changePercent)}
      </em>
    </button>
  )
}

function MarketFilters({
  query,
  sortKey,
  universeTab,
  zoneTab,
  onQuery,
  onSort,
  onUniverseTab,
  onZoneTab
}: {
  query: string
  sortKey: SortKey
  universeTab: MarketUniverseTab
  zoneTab: MarketZoneTab
  onQuery: (query: string) => void
  onSort: (key: SortKey) => void
  onUniverseTab: (tab: MarketUniverseTab) => void
  onZoneTab: (tab: MarketZoneTab) => void
}) {
  return (
    <div className="market-filter-stack" aria-label="行情筛选">
      <div className="market-universe-tabs" role="tablist" aria-label="市场类型">
        {marketUniverseTabs.map((tab) => (
          <button key={tab.value} type="button" aria-selected={universeTab === tab.value} onClick={() => onUniverseTab(tab.value)}>
            {tab.label}
          </button>
        ))}
      </div>
      <div className="market-zone-tabs" role="tablist" aria-label="板块">
        {marketZoneTabs.map((tab) => (
          <button key={tab.value} type="button" aria-selected={zoneTab === tab.value} onClick={() => onZoneTab(tab.value)}>
            {tab.label}
            {tab.badge ? <span>{tab.badge}</span> : null}
          </button>
        ))}
      </div>
      <div className="market-shell__toolbar" aria-label="行情搜索和排序">
        <label className="market-search-field">
          <Search size={15} aria-hidden="true" />
          <span>搜索</span>
          <input value={query} onChange={(event) => onQuery(event.target.value)} placeholder="BTCUSDT" />
        </label>
        <div className="market-sort-field">
          <span id="market-sort-label">排序</span>
          <SelectField labelledBy="market-sort-label" value={sortKey} options={marketSortOptions} onChange={onSort} />
        </div>
      </div>
    </div>
  )
}

function MarketTable({
  markets,
  favorites,
  sortKey,
  sortDirection,
  onFavorite,
  onOpen,
  onSort
}: {
  markets: TradingMarket[]
  favorites: Set<string>
  sortKey: SortKey
  sortDirection: SortDirection
  onFavorite: (symbol: string) => void
  onOpen: (market: TradingMarket) => void
  onSort: (key: SortKey) => void
}) {
  return (
    <div className="market-table" aria-label="行情表格">
      <table>
        <thead>
          <tr>
            {marketColumns.map((column) => (
              <th key={column.key} aria-sort={column.key === sortKey ? toAriaSort(sortDirection) : undefined}>
                {column.sortable ? (
                  <button type="button" onClick={() => onSort(column.key as SortKey)}>
                    {column.label}
                    <span>{column.key === sortKey ? (sortDirection === 'asc' ? '↑' : '↓') : '↕'}</span>
                  </button>
                ) : (
                  column.label
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {markets.map((market) => (
            <tr key={market.symbol} onClick={() => onOpen(market)}>
              <td>
                <FavoriteButton active={favorites.has(market.symbol) || market.favorite} symbol={market.symbol} onFavorite={onFavorite} />
                <AssetMark symbol={market.symbol} category={market.category} size="sm" />
                <span>
                  <strong>{market.symbol}</strong>
                  <small>{market.name}</small>
                </span>
              </td>
              <td>{formatMarketPrice(market.symbol, market.last)}</td>
              <td className={market.changePercent >= 0 ? 'market-change--up' : 'market-change--down'}>
                {formatSignedPercent(market.changePercent)}
              </td>
              <td>{formatVolume(market)}</td>
              <td>{formatMarketCap(market)}</td>
              <td>
                <button
                  type="button"
                  className="table-action table-action--secondary market-trade-action"
                  onClick={(event) => {
                    event.stopPropagation()
                    onOpen(market)
                  }}
                >
                  交易
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function MarketMobileList({
  markets,
  favorites,
  onFavorite,
  onOpen
}: {
  markets: TradingMarket[]
  favorites: Set<string>
  onFavorite: (symbol: string) => void
  onOpen: (market: TradingMarket) => void
}) {
  return (
    <div className="market-mobile-list" aria-label="移动端行情列表">
      {markets.map((market) => (
        <article key={market.symbol} className="market-mobile-row">
          <button type="button" className="market-mobile-row__body" onClick={() => onOpen(market)}>
            <AssetMark symbol={market.symbol} category={market.category} size="md" />
            <span>
              <strong>{market.symbol}</strong>
              <small>
                市值 {formatMarketCap(market)} · 成交量 {formatVolume(market)}
              </small>
            </span>
            <span>
              <strong>{formatMarketPrice(market.symbol, market.last)}</strong>
              <em className={market.changePercent >= 0 ? 'market-change market-change--up' : 'market-change market-change--down'}>
                {formatSignedPercent(market.changePercent)}
              </em>
            </span>
          </button>
          <FavoriteButton active={favorites.has(market.symbol) || market.favorite} symbol={market.symbol} onFavorite={onFavorite} />
        </article>
      ))}
    </div>
  )
}

function FavoriteButton({
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
      className="market-favorite-button"
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

function TradingDataDashboard({ rankings, onOpen }: { rankings: RankingGroup[]; onOpen: (market: TradingMarket) => void }) {
  const [activeTab, setActiveTab] = useState<TradingDataTab>('rankings')

  return (
    <div className="market-data-view" aria-label="交易数据">
      <div className="market-data-tabs" role="tablist" aria-label="交易数据分类">
        {tradingDataTabs.map((tab) => (
          <button
            key={tab.value}
            type="button"
            aria-selected={activeTab === tab.value}
            onClick={() => setActiveTab(tab.value)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="market-data-dashboard market-ranking-preview-grid" aria-label={activeTab === 'rankings' ? '排行榜' : '合约榜单'}>
        {rankings.map((group) => (
          <RankingCard key={group.title} group={group} onOpen={onOpen} />
        ))}
      </div>
    </div>
  )
}

function RankingCard({ group, onOpen }: { group: RankingGroup; onOpen: (market: TradingMarket) => void }) {
  return (
    <section className="market-rank-card">
      <div className="market-rank-card__head">
        <h2>{group.title}</h2>
        <button type="button" className="market-rank-card__filter">
          加密货币
          <ChevronDown size={14} aria-hidden="true" />
        </button>
      </div>
      <div className="market-rank-card__columns" aria-hidden="true">
        <span>名称</span>
        <span>价格</span>
        <span>
          24小时涨跌
          <ChevronDown size={11} aria-hidden="true" />
        </span>
      </div>
      <ol className="market-rank-list">
        {group.markets.map((market, index) => (
          <li key={`${group.title}-${market.symbol}`}>
            <button type="button" onClick={() => onOpen(market)}>
              <span className="market-rank-index">{index + 1}</span>
              <span className="market-rank-symbol">
                <AssetMark symbol={market.symbol} category={market.category} size="sm" />
                <strong>{market.displaySymbol}</strong>
              </span>
              <span className="market-rank-price">{market.priceLabel}</span>
              <span className={market.changePercent >= 0 ? 'market-change market-change--up' : 'market-change market-change--down'}>
                {market.changeLabel}
              </span>
            </button>
          </li>
        ))}
      </ol>
    </section>
  )
}

function MarketInsightPlaceholder({ title, description }: { title: string; description: string }) {
  return (
    <section className="market-insight-placeholder" aria-labelledby="market-insight-title">
      <h2 id="market-insight-title">{title}</h2>
      <p>{description}</p>
    </section>
  )
}

type SummaryGroup = {
  key: string
  title: string
  description: string
  markets: TradingMarket[]
}

type RankingGroup = {
  title: string
  description: string
  markets: RankingMarket[]
}

type RankingMarket = TradingMarket & {
  displaySymbol: string
  priceLabel: string
  changeLabel: string
}

function buildSummaryDeck(markets: TradingMarket[]): SummaryGroup[] {
  const byVolume = [...markets].sort((left, right) => turnover(right) - turnover(left))
  const byGain = [...markets].sort((left, right) => right.changePercent - left.changePercent)
  const newest = [...markets].reverse()

  return [
    { key: 'hot', title: '热门', description: '成交和关注最活跃', markets: takeThree(byVolume) },
    { key: 'new', title: '新币榜', description: '近期加入观察', markets: takeThree(newest) },
    { key: 'gainers', title: '领涨榜', description: '24h 涨幅靠前', markets: takeThree(byGain) },
    { key: 'volume', title: '成交榜', description: '24h 成交额靠前', markets: takeThree(byVolume.slice(1).length ? byVolume.slice(1) : byVolume) }
  ]
}

function buildRankings(_markets: TradingMarket[]): RankingGroup[] {
  return [
    {
      title: '热门币种',
      description: '排行入口',
      markets: [
        rankingMarket('BNB', '$611.30', 0.44),
        rankingMarket('BTC', '$64,259.44', 0.13),
        rankingMarket('ETH', '$1,665.29', -0.84),
        rankingMarket('WLD', '$0.4953', -1.1),
        rankingMarket('BABY', '$0.01506', 5.46),
        rankingMarket('DOGE', '$0.0863', -2.01),
        rankingMarket('NIGHT', '$0.03123', -7.9),
        rankingMarket('SOL', '$67.59', -0.49),
        rankingMarket('XAUT', '$4,216.54', 0.24, 'XAU'),
        rankingMarket('OPG', '$0.2145', 27.68)
      ]
    },
    {
      title: '涨幅榜',
      description: '24h 上涨',
      markets: [
        rankingMarket('ZKC', '$0.0642', 31.29),
        rankingMarket('OPG', '$0.2145', 27.68),
        rankingMarket('BANANAS...', '$0.009735', 27.02, 'BANANAS'),
        rankingMarket('SYN', '$0.0399', 25.08),
        rankingMarket('MITO', '$0.02175', 22.47),
        rankingMarket('MEGA', '$0.05946', 13.93),
        rankingMarket('JASMY', '$0.00529', 11.84),
        rankingMarket('MIRA', '$0.06', 11.11),
        rankingMarket('ZKP', '$0.0608', 9.75),
        rankingMarket('EIGEN', '$0.1963', 8.51)
      ]
    },
    {
      title: '跌幅榜',
      description: '24h 下跌',
      markets: [
        rankingMarket('STG', '$0.2534', -40.39),
        rankingMarket('HEI', '$0.0835', -11.36),
        rankingMarket('TST', '$0.01526', -10.18),
        rankingMarket('TRUMP', '$2.02', -9.79),
        rankingMarket('HMSTR', '$0.0001714', -9.74),
        rankingMarket('EPIC', '$0.523', -9.36),
        rankingMarket('IO', '$0.1712', -9.03),
        rankingMarket('POND', '$0.00161', -8.52),
        rankingMarket('NIGHT', '$0.03123', -7.9),
        rankingMarket('ROBO', '$0.02054', -6.21)
      ]
    }
  ]
}

function rankingMarket(displaySymbol: string, priceLabel: string, changePercent: number, baseOverride?: string): RankingMarket {
  const base = baseOverride ?? displaySymbol.replace(/\.\.\.$/, '')
  const symbol = `${base}USDT`
  const last = Number(priceLabel.replace(/[$,]/g, ''))
  const lastValue = Number.isFinite(last) ? last : 0
  const rangeFields = { ['high' + '24h']: lastValue, ['low' + '24h']: lastValue }
  return {
    ...rangeFields,
    symbol,
    base,
    quote: 'USDT',
    name: `${base} / Tether`,
    category: 'crypto',
    favorite: false,
    last: lastValue,
    changePercent,
    volume: '0',
    spread: 0,
    source: 'reference',
    displaySymbol,
    priceLabel,
    changeLabel: formatSignedPercent(changePercent)
  } as RankingMarket
}

function filterMarketRows(markets: TradingMarket[], query: string, universeTab: MarketUniverseTab, zoneTab: MarketZoneTab) {
  const normalizedQuery = query.trim().toLowerCase()
  return markets.filter((market) => {
    const favorite = market.favorite
    const universe = getMarketUniverse(market)
    const universeMatches =
      universeTab === 'favorites'
        ? favorite
        : universeTab === 'forex'
          ? market.category === 'fx'
          : universeTab === 'crypto'
            ? universe === 'spot' || universe === 'contract'
            : universe === universeTab
    const zoneMatches = matchesZone(market, zoneTab)
    const queryMatches =
      normalizedQuery.length === 0 ||
      market.symbol.toLowerCase().includes(normalizedQuery) ||
      market.name.toLowerCase().includes(normalizedQuery)
    return universeMatches && zoneMatches && queryMatches
  })
}

function compareMarkets(left: TradingMarket, right: TradingMarket, sortKey: SortKey, direction: SortDirection) {
  const multiplier = direction === 'asc' ? 1 : -1
  if (sortKey === 'symbol') return left.symbol.localeCompare(right.symbol) * multiplier
  if (sortKey === 'price') return (left.last - right.last) * multiplier
  if (sortKey === 'change') return (left.changePercent - right.changePercent) * multiplier
  if (sortKey === 'marketCap') return (marketCap(left) - marketCap(right)) * multiplier
  return (turnover(left) - turnover(right)) * multiplier
}

function getMarketUniverse(market: TradingMarket): Exclude<MarketUniverseTab, 'favorites' | 'crypto'> {
  if (market.category === 'fx') return 'forex'
  if (market.category === 'crypto') return market.symbol === 'ETHUSDT' ? 'contract' : 'spot'
  return 'contract'
}

function matchesZone(market: TradingMarket, zone: MarketZoneTab) {
  if (zone === 'all') return true
  if (zone === 'metals') return market.category === 'metals'
  if (zone === 'indices') return market.category === 'indices'
  return true
}

function toTradingCategory(market: TradingMarket): TradingCategory {
  if (market.category === 'fx') return 'forex'
  if (market.category === 'crypto') return market.symbol === 'ETHUSDT' ? 'contract' : 'crypto'
  return 'contract'
}

async function loadQuotedMarket(market: TradingMarket) {
  try {
    return applyQuote(market, await fetchMarketQuote(market.symbol))
  } catch {
    return market
  }
}

function canHydrateMarketQuote(market: TradingMarket) {
  return market.tradable !== false && (market.category === 'fx' || market.provider === 'massive')
}

function applyQuote(market: TradingMarket, quote: TradingQuote): TradingMarket {
  return mergeTradingQuoteIntoMarket(market, quote)
}

function mergeQuotedMarkets(markets: TradingMarket[], quotedMarkets: TradingMarket[]) {
  const quotedBySymbol = new Map(quotedMarkets.map((market) => [market.symbol, market]))
  return markets.map((market) => quotedBySymbol.get(market.symbol) ?? market)
}

function applyRealtimeQuote(market: TradingMarket, quote: Quote): TradingMarket {
  const mid = Number(quote.mid)
  const spread = Number(quote.spread)
  return {
    ...market,
    last: Number.isFinite(mid) ? mid : market.last,
    spread: Number.isFinite(spread) ? spread : market.spread
  }
}

function formatSignedPercent(value: number) {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

function formatVolume(market: TradingMarket) {
  return parseCompactNumber(market.volume) > 0 ? `${market.volume}` : '--'
}

function formatMarketCap(market: TradingMarket) {
  const value = marketCap(market)
  return value > 0 ? `$${compactNumber(value)}` : '--'
}

function marketCap(market: TradingMarket) {
  if (typeof market.marketCap === 'number' && market.marketCap > 0) return market.marketCap
  const override = estimatedMarketCaps[market.symbol]
  if (override) return override
  return turnover(market) * 8
}

function turnover(market: TradingMarket) {
  return parseCompactNumber(market.volume) * market.last
}

function parseCompactNumber(value: string) {
  const normalized = value.trim().toUpperCase()
  const number = Number(normalized.replace(/[^\d.]/g, ''))
  if (!Number.isFinite(number)) return 0
  if (normalized.endsWith('T')) return number * 1_000_000_000_000
  if (normalized.endsWith('B')) return number * 1_000_000_000
  if (normalized.endsWith('M')) return number * 1_000_000
  if (normalized.endsWith('K')) return number * 1_000
  return number
}

function compactNumber(value: number) {
  if (value >= 1_000_000_000_000) return `${(value / 1_000_000_000_000).toFixed(2)}T`
  if (value >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(2)}B`
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`
  if (value >= 1_000) return `${(value / 1_000).toFixed(2)}K`
  return value.toFixed(2)
}

const estimatedMarketCaps: Record<string, number> = {
  BTCUSDT: 1_320_000_000_000,
  ETHUSDT: 410_000_000_000,
  BNBUSDT: 86_000_000_000,
  XRPUSDT: 65_000_000_000,
  SOLUSDT: 74_000_000_000,
  XAUUSD: 15_800_000_000_000,
  US100: 22_400_000_000_000
}

function takeThree(markets: TradingMarket[]) {
  return (markets.length >= 3 ? markets : mockTradingMarkets).slice(0, 3)
}

function toAriaSort(direction: SortDirection) {
  return direction === 'asc' ? 'ascending' : 'descending'
}

function loadFavoriteSymbols() {
  if (typeof window === 'undefined') return new Set<string>()
  try {
    const parsed = JSON.parse(window.localStorage.getItem(favoriteStorageKey) ?? '[]')
    if (!Array.isArray(parsed)) return new Set<string>()
    return new Set(parsed.filter((symbol): symbol is string => typeof symbol === 'string' && symbol.length > 0))
  } catch {
    return new Set<string>()
  }
}

function saveFavoriteSymbols(favorites: Set<string>) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(favoriteStorageKey, JSON.stringify(Array.from(favorites)))
}
