import { generateMarketTicks } from '../generator/marketPath.ts'
import type { TradingLabScenario } from '../model/types.ts'
import { calculateScenario } from '../oracle/runOracle.ts'
import type { OracleCalculationOutcome } from '../oracle/types.ts'
import {
  selectVisibleTickWindow,
  type VisibleTickWindow,
} from '../chart/visibleTickWindow.ts'

export type LocalExpectedWorkspaceCalculation = Readonly<{
  outcome: OracleCalculationOutcome
  localTickWindow: VisibleTickWindow
}>

function calculateLocalExpectedArtifacts(
  scenario: TradingLabScenario,
) {
  const ticks = generateMarketTicks({
    path: scenario.marketPath,
    configSnapshot: scenario.configSnapshot,
  })
  const outcome = calculateScenario({
    scenario,
    market: {
      virtualStart: scenario.marketPath.virtualStart,
      ticks,
    },
  })
  return { outcome, ticks }
}

export function calculateLocalExpectedScenario(
  scenario: TradingLabScenario,
): OracleCalculationOutcome {
  return calculateLocalExpectedArtifacts(scenario).outcome
}

export function calculateLocalExpectedWorkspace(
  scenario: TradingLabScenario,
): LocalExpectedWorkspaceCalculation {
  const artifacts = calculateLocalExpectedArtifacts(scenario)
  return {
    outcome: artifacts.outcome,
    localTickWindow: selectVisibleTickWindow(artifacts.ticks),
  }
}
