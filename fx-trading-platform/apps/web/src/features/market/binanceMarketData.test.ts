import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  buildBinanceFuturesDashboard,
  mapBinanceProductOverview,
  type BinanceFuturesDashboardPayload,
  type BinanceProduct
} from './binanceMarketData.ts'

describe('binance market data mappers', () => {
  it('maps Binance product overview into live market rows, aggregate volume, market cap and hot tokens', () => {
    const products: BinanceProduct[] = [
      {
        s: 'BTCUSDT',
        st: 'TRADING',
        b: 'BTC',
        q: 'USDT',
        an: 'Bitcoin',
        qn: 'Tether',
        o: '64000',
        h: '66000',
        l: '63000',
        c: '65000',
        v: '30000',
        qv: '2000000000',
        cs: 20000000,
        tags: ['pow']
      },
      {
        s: 'ETHUSDT',
        st: 'TRADING',
        b: 'ETH',
        q: 'USDT',
        an: 'Ethereum',
        qn: 'Tether',
        o: '3000',
        h: '3600',
        l: '2900',
        c: '3500',
        v: '420000',
        qv: '1500000000',
        cs: 120000000,
        tags: ['defi']
      },
      {
        s: 'ETHBTC',
        st: 'TRADING',
        b: 'ETH',
        q: 'BTC',
        an: 'Ethereum',
        qn: 'Bitcoin',
        o: '0.05',
        h: '0.06',
        l: '0.04',
        c: '0.055',
        v: '1000',
        qv: '55',
        cs: 120000000,
        tags: []
      },
      {
        s: 'BTCUPUSDT',
        st: 'TRADING',
        b: 'BTCUP',
        q: 'USDT',
        an: 'BTCUP',
        qn: 'Tether',
        o: '1',
        h: '1',
        l: '1',
        c: '1',
        v: '1',
        qv: '1',
        cs: 1,
        tags: []
      }
    ]

    const overview = mapBinanceProductOverview(products, {
      value: 20,
      label: 'Extreme Fear',
      updatedAt: 1_781_481_600_000,
      source: 'alternative.me'
    })

    assert.deepEqual(
      overview.markets.map((market) => market.symbol),
      ['BTCUSDT', 'ETHUSDT']
    )
    assert.equal(overview.markets[0]?.marketCap, 1_300_000_000_000)
    assert.equal(overview.markets[0]?.changePercent, 1.5625)
    assert.equal(overview.markets[0]?.provider, 'binance')
    assert.equal(overview.markets[0]?.source, 'binance-market-overview')
    assert.equal(overview.hotTokens[0]?.symbol, 'BTCUSDT')
    assert.equal(overview.metrics.marketCap, 1_720_000_000_000)
    assert.equal(overview.metrics.volume24h, 3_500_000_000)
    assert.equal(overview.metrics.fearGreed?.label, 'Extreme Fear')
  })

  it('builds perpetual trading-data panels from Binance futures statistics', () => {
    const payload: BinanceFuturesDashboardPayload = {
      ticker: {
        symbol: 'BTCUSDT',
        highPrice: '65996.80',
        lastPrice: '65743.60',
        lowPrice: '63650.00',
        priceChangePercent: '2.007',
        quoteVolume: '9187252385.05',
        volume: '141341.726',
        closeTime: 1_781_513_316_208
      },
      openInterest: [
        {
          sumOpenInterest: '102991.46200000',
          sumOpenInterestValue: '6770143754.57000000',
          CMCCirculatingSupply: '20042828.00000000',
          timestamp: 1_781_513_100_000
        },
        {
          sumOpenInterest: '103016.72500000',
          sumOpenInterestValue: '6770707051.13198950',
          CMCCirculatingSupply: '20042828.00000000',
          timestamp: 1_781_513_400_000
        }
      ],
      topAccountRatio: [
        { longShortRatio: '1.44', longAccount: '0.59', shortAccount: '0.41', timestamp: 1_781_513_100_000 },
        { longShortRatio: '1.46', longAccount: '0.60', shortAccount: '0.40', timestamp: 1_781_513_400_000 }
      ],
      topPositionRatio: [
        { longShortRatio: '1.18', longAccount: '0.54', shortAccount: '0.46', timestamp: 1_781_513_100_000 },
        { longShortRatio: '1.20', longAccount: '0.55', shortAccount: '0.45', timestamp: 1_781_513_400_000 }
      ],
      globalLongShortRatio: [
        { longShortRatio: '1.32', longAccount: '0.57', shortAccount: '0.43', timestamp: 1_781_513_100_000 },
        { longShortRatio: '1.34', longAccount: '0.58', shortAccount: '0.42', timestamp: 1_781_513_400_000 }
      ],
      takerBuySell: [
        { buySellRatio: '1.10', buyVol: '410', sellVol: '370', timestamp: 1_781_513_100_000 },
        { buySellRatio: '1.30', buyVol: '650', sellVol: '500', timestamp: 1_781_513_400_000 }
      ],
      basis: [
        { futuresPrice: '65730', indexPrice: '65740', basis: '-10', basisRate: '-0.000152', timestamp: 1_781_513_100_000 },
        { futuresPrice: '65743.6', indexPrice: '65750', basis: '-6.4', basisRate: '-0.000097', timestamp: 1_781_513_400_000 }
      ],
      fundingRates: [
        { fundingRate: '0.00000621', fundingTime: 1_781_513_100_000 },
        { fundingRate: '0.00000645', fundingTime: 1_781_513_400_000 }
      ]
    }

    const dashboard = buildBinanceFuturesDashboard(payload)

    assert.equal(dashboard.referenceMarket.symbol, 'BTCUSDT')
    assert.equal(dashboard.referenceMarket.last, 65743.6)
    assert.equal(dashboard.referenceMarket.marketCap, 1_317_687_666_900.8)
    assert.deepEqual(
      dashboard.panels.map((panel) => panel.title),
      [
        '合约持仓量',
        '大户账户数多空比',
        '大户持仓量多空比',
        '多空账户数比',
        '合约主动买卖量',
        '基差',
        '资金费率: 0.000645%',
        '未平仓量与市值比率'
      ]
    )
    assert.equal(dashboard.panels[0]?.labels.length, 2)
    assert.match(dashboard.panels[0]?.labels[0] ?? '', /^\d{2}:\d{2}$/)
    assert.equal(dashboard.panels[0]?.series[0]?.values.at(-1), 103016.725)
    assert.equal(dashboard.panels[4]?.series[0]?.values.at(-1), 500)
    assert.equal(dashboard.panels[4]?.series[1]?.values.at(-1), 650)
    assert.equal(dashboard.panels[5]?.series[2]?.values.at(-1), -6.4)
  })
})
