import { resolveSafeTradingPath } from '../../app/tradingRoutes.ts'
import type { PositionResponse, UpdatePositionProtectionPayload } from '@fx-platform/frontend-core'

type LegacyProtectionUpdate = (
  position: PositionResponse,
  payload: UpdatePositionProtectionPayload
) => Promise<unknown> | unknown

export function resolvePositionProtectionPath(position: PositionResponse) {
  if (!isLinearPerpetual(position)) return null
  const compactSymbol = position.symbol.trim().toUpperCase().replace(/[-_/]/g, '')
  const baseSymbol = compactSymbol.endsWith('PERP') ? compactSymbol.slice(0, -4) : compactSymbol
  return resolveSafeTradingPath('perpetual', `${baseSymbol}-PERP`)
}

export function canUseLegacyPositionProtection(position: PositionResponse) {
  return resolvePositionProtectionPath(position) === null
}

export async function executePositionProtectionUpdate(
  position: PositionResponse,
  payload: UpdatePositionProtectionPayload,
  updateLegacy: LegacyProtectionUpdate
) {
  const path = resolvePositionProtectionPath(position)
  if (path) return { kind: 'canonical' as const, path }
  await updateLegacy(position, payload)
  return { kind: 'legacy' as const }
}

function isLinearPerpetual(position: PositionResponse) {
  return position.productType === 'LINEAR_PERP'
    || position.instrumentType?.trim().toUpperCase() === 'LINEAR_PERP'
    || position.symbol.trim().toUpperCase().endsWith('-PERP')
}
