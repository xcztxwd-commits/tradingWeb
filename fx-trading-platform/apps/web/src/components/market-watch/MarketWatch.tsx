import type { Quote, SymbolItem } from '../../types/trading'

type Props = {
  symbols: SymbolItem[]
  quotes: Record<string, Quote>
  selectedSymbol: string
  onSelect: (symbol: string) => void
}

export function MarketWatch({ symbols, quotes, selectedSymbol, onSelect }: Props) {
  return (
    <section className="panel market-watch">
      <header className="panel-header">
        <h2>Market Watch</h2>
        <span>{symbols.length} symbols</span>
      </header>
      <div className="symbol-list">
        {symbols.map((item) => {
          const quote = quotes[item.symbol]
          return (
            <button
              key={item.symbol}
              className={item.symbol === selectedSymbol ? 'symbol-row active' : 'symbol-row'}
              onClick={() => onSelect(item.symbol)}
            >
              <span>
                <strong>{item.symbol}</strong>
                <small>{item.displayName}</small>
              </span>
              <span className="quote-pair">
                <b>{quote?.bid ?? '-'}</b>
                <b>{quote?.ask ?? '-'}</b>
              </span>
            </button>
          )
        })}
      </div>
    </section>
  )
}
