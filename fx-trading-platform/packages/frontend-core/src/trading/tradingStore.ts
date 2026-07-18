import { create } from 'zustand'

import type { Quote, SymbolItem } from '../models/index.ts'

type TradingState = {
  selectedSymbol: string
  symbols: SymbolItem[]
  quotes: Record<string, Quote>
  setSelectedSymbol: (symbol: string) => void
  setSymbols: (symbols: SymbolItem[]) => void
  setQuote: (quote: Quote) => void
}

export const useTradingStore = create<TradingState>((set) => ({
  selectedSymbol: 'EURUSD',
  symbols: [],
  quotes: {},
  setSelectedSymbol: (selectedSymbol) => set({ selectedSymbol }),
  setSymbols: (symbols) => set({ symbols }),
  setQuote: (quote) => set((state) => ({ quotes: { ...state.quotes, [quote.symbol]: quote } }))
}))
