import type { QuantityUnit, TriggerExecutionType, UpdateProtectionRequest } from '@fx-platform/shared-types'

import type { OrderResponse, UpdateOrderPayload } from '@fx-platform/frontend-core'

export type EditFields = Record<string, unknown>

export function buildNormalOrderUpdatePayload(fields: EditFields): UpdateOrderPayload {
  return compact({
    quantity: positiveNumber(fields.quantity),
    price: positiveNumber(fields.price)
  })
}

export function buildProtectionUpdatePayload(
  order: Pick<OrderResponse, 'version' | 'quantityUnit'>,
  fields: EditFields
): UpdateProtectionRequest | null {
  if (!Number.isSafeInteger(order.version) || Number(order.version) < 0) return null

  const quantity = positiveNumber(fields.quantity)
  const triggerExecutionType = validExecutionType(fields.triggerExecutionType)
  return compact({
    quantity,
    quantityUnit: quantity === undefined ? undefined : validQuantityUnit(order.quantityUnit),
    triggerPrice: positiveNumber(fields.triggerPrice),
    triggerExecutionType,
    price: triggerExecutionType === 'MARKET' ? undefined : positiveNumber(fields.price),
    expectedVersion: Number(order.version)
  }) as UpdateProtectionRequest
}

function compact<T extends Record<string, unknown>>(values: T) {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== undefined)) as T
}

function positiveNumber(value: unknown) {
  const parsed = Number(String(value ?? '').trim())
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}

function validQuantityUnit(value: unknown): QuantityUnit | undefined {
  return value === 'BASE' || value === 'QUOTE' || value === 'CONTRACTS' ? value : undefined
}

function validExecutionType(value: unknown): TriggerExecutionType | undefined {
  return value === 'MARKET' || value === 'LIMIT' ? value : undefined
}
