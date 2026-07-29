import { normalizeScenario } from './normalization.ts'
import type {
  InitialBalances,
  MarketPathDefinition,
  ScenarioDefaults,
  ScenarioSymbol,
  TradingLabConfigSnapshot,
  TradingLabScenario,
} from './types.ts'

export type DefaultScenarioInput = {
  id: string
  seed: string
  configSnapshot: TradingLabConfigSnapshot
  configSnapshotHash: string
  initialBalances: InitialBalances
  defaults: ScenarioDefaults
  symbols: ScenarioSymbol[]
  marketPath: MarketPathDefinition
  name?: string
  description?: string
  negativeMode?: boolean
}

export function createDefaultScenario(input: DefaultScenarioInput): TradingLabScenario {
  return normalizeScenario({
    id: input.id,
    name: input.name ?? '未命名场景',
    description: input.description ?? '',
    negativeMode: input.negativeMode ?? false,
    seed: input.seed,
    modelVersion: input.configSnapshot.modelVersion,
    configSnapshot: input.configSnapshot,
    configSnapshotHash: input.configSnapshotHash,
    executionPolicy: input.configSnapshot.executionPolicy,
    marketPath: input.marketPath,
    initialBalances: input.initialBalances,
    defaults: input.defaults,
    symbols: input.symbols,
    timeline: [],
  })
}
