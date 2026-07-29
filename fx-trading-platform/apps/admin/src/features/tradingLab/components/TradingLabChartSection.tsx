import {
  useEffect,
  useMemo,
  useState,
} from 'react'

import type { LocalExpectedState } from '../localExpected/localOracleScheduler.ts'
import type {
  PriceType,
  ProductType,
  TradingLabScenario,
} from '../model/types.ts'
import type { MarketTick } from '../oracle/types.ts'
import type { TradingLabChartMarker } from '../chart/chartMarkers.ts'
import {
  MAX_VISIBLE_TICK_LIMIT,
  selectVisibleTickWindow,
} from '../chart/visibleTickWindow.ts'
import {
  TradingLabChart,
  type TradingLabChartMode,
  type TradingLabChartPeriod,
} from './TradingLabChart.tsx'

export type TradingLabChartSectionProps = Readonly<{
  scenario: TradingLabScenario
  localExpected: LocalExpectedState
  actualTicks: readonly MarketTick[]
  markers: readonly TradingLabChartMarker[]
}>

type ChartInstrument = Readonly<{
  key: string
  productType: ProductType
  symbol: string
  pricePrecision: number
}>

const PRICE_TYPES: readonly PriceType[] = [
  'BID',
  'ASK',
  'LAST',
  'MARK',
  'INDEX',
]
const EMPTY_MARKET_TICKS =
  Object.freeze([]) as readonly MarketTick[]

function instrumentKey(productType: ProductType, symbol: string): string {
  return `${productType}:${symbol}`
}

function chartInstruments(
  scenario: TradingLabScenario,
): readonly ChartInstrument[] {
  const result: ChartInstrument[] = []
  const seen = new Set<string>()
  for (const selected of scenario.symbols) {
    const matches = scenario.configSnapshot.instruments.filter(
      (candidate) =>
        candidate.productType === selected.productType
        && candidate.symbol === selected.symbol,
    )
    const instrument = matches[0]
    const key = instrumentKey(selected.productType, selected.symbol)
    if (
      matches.length !== 1
      || instrument === undefined
      || seen.has(key)
    ) {
      return []
    }
    seen.add(key)
    result.push({
      key,
      productType: instrument.productType,
      symbol: instrument.symbol,
      pricePrecision: instrument.pricePrecision,
    })
  }
  return result
}

function boundedLocalTicks(
  state: LocalExpectedState,
): readonly MarketTick[] {
  return 'localTickWindow' in state
    ? state.localTickWindow.ticks
    : EMPTY_MARKET_TICKS
}

export function TradingLabChartSection({
  scenario,
  localExpected,
  actualTicks,
  markers,
}: TradingLabChartSectionProps) {
  const instruments = useMemo(
    () => chartInstruments(scenario),
    [scenario],
  )
  const [selectedKey, setSelectedKey] = useState(
    () => instruments[0]?.key ?? '',
  )
  const [mode, setMode] = useState<TradingLabChartMode>('KLINE')
  const [period, setPeriod] = useState<TradingLabChartPeriod>('1m')
  const [visiblePrices, setVisiblePrices] = useState<ReadonlySet<PriceType>>(
    () => new Set<PriceType>(['LAST', 'MARK']),
  )

  useEffect(() => {
    if (!instruments.some((instrument) => instrument.key === selectedKey)) {
      setSelectedKey(instruments[0]?.key ?? '')
    }
  }, [instruments, selectedKey])

  const selectedInstrument = instruments.find(
    (instrument) => instrument.key === selectedKey,
  )
  const localTicks = boundedLocalTicks(localExpected)
  const actualWindow = useMemo(
    () => selectVisibleTickWindow(actualTicks, {
      limit: MAX_VISIBLE_TICK_LIMIT,
    }),
    [actualTicks],
  )
  const virtualTime = actualWindow.ticks.at(-1)?.virtualTime
    ?? localTicks.at(-1)?.virtualTime
    ?? scenario.marketPath.virtualStart

  const togglePrice = (priceType: PriceType): void => {
    setVisiblePrices((current) => {
      const next = new Set(current)
      if (next.has(priceType)) {
        next.delete(priceType)
      } else {
        next.add(priceType)
      }
      return next
    })
  }

  return (
    <section
      className="trading-lab-panel trading-lab-chart-section"
      aria-labelledby="trading-lab-chart-heading"
    >
      <div className="trading-lab-chart-heading">
        <div>
          <h2 id="trading-lab-chart-heading">路径图表</h2>
          <p>
            LOCAL 仅来自 bounded 本地计算窗口；
            {actualWindow.ticks.length === 0
              ? '尚无实际价格数据，ACTUAL 不会用 LOCAL 冒充。'
              : 'ACTUAL 仅来自 durable MARKET_TICK 与 checkpoint evidence。'}
          </p>
          <p>
            ACTUAL marker 不回填 LOCAL；价格或 actionId 无法无损证明时，
            仅显示 durable 时间线且对应字段保持空值。
          </p>
        </div>
        <span className="trading-lab-chart-window">
          LOCAL {localTicks.length.toLocaleString()} / 10,000
          {' · '}
          ACTUAL {actualWindow.ticks.length.toLocaleString()} / 10,000
        </span>
      </div>

      <div className="trading-lab-chart-controls">
        <div className="trading-lab-chart-tabs" aria-label="图表品种">
          {instruments.map((instrument) => (
            <button
              key={instrument.key}
              type="button"
              aria-pressed={selectedKey === instrument.key}
              onClick={() => setSelectedKey(instrument.key)}
            >
              {instrument.productType === 'CRYPTO_SPOT' ? 'Spot' : 'Perp'}
              {' '}
              {instrument.symbol}
            </button>
          ))}
        </div>

        <div className="trading-lab-chart-tabs" aria-label="图表模式">
          {(['TICK', 'KLINE'] as const).map((candidate) => (
            <button
              key={candidate}
              type="button"
              aria-pressed={mode === candidate}
              onClick={() => setMode(candidate)}
            >
              {candidate}
            </button>
          ))}
        </div>

        <label>
          周期
          <select
            value={period}
            disabled={mode === 'TICK'}
            onChange={(event) => {
              setPeriod(event.currentTarget.value as TradingLabChartPeriod)
            }}
          >
            {(['1s', '1m', '5m', '15m', '1h'] as const).map((candidate) => (
              <option key={candidate} value={candidate}>{candidate}</option>
            ))}
          </select>
        </label>

        <fieldset className="trading-lab-price-switches">
          <legend>价格线</legend>
          {PRICE_TYPES.map((priceType) => (
            <label key={priceType}>
              <input
                type="checkbox"
                checked={visiblePrices.has(priceType)}
                onChange={() => togglePrice(priceType)}
              />
              {priceType}
            </label>
          ))}
        </fieldset>
      </div>

      {selectedInstrument === undefined ? (
        <div className="state-block">
          场景的品种与配置快照无法形成唯一 composite identity。
        </div>
      ) : (
        <TradingLabChart
          productType={selectedInstrument.productType}
          symbol={selectedInstrument.symbol}
          pricePrecision={selectedInstrument.pricePrecision}
          ticks={actualWindow.ticks}
          localTicks={localTicks}
          markers={markers}
          period={period}
          mode={mode}
          visiblePrices={visiblePrices}
          virtualTime={virtualTime}
        />
      )}
    </section>
  )
}
