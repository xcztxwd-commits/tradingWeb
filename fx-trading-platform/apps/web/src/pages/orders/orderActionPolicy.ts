import type { TFunction } from 'i18next'

type OrderLike = {
  status: string
}

type OrderActionPolicy = {
  canCancel: boolean
  canModify: boolean
  cancelReasonKey?: string
  modifyReasonKey?: string
  reasonStatus?: string
}

const currentOrderStatuses = new Set(['PENDING', 'ACCEPTED', 'WORKING', 'PARTIALLY_FILLED'])

export function isCurrentOrderStatus(status: string) {
  return currentOrderStatuses.has(normalizeStatus(status))
}

export function getOrderActionPolicy(order: OrderLike): OrderActionPolicy {
  const status = normalizeStatus(order.status)
  if (status === 'PENDING') {
    return { canCancel: true, canModify: true }
  }

  return {
    canCancel: false,
    canModify: false,
    cancelReasonKey: getUnavailableReasonKey(status),
    modifyReasonKey: getUnavailableReasonKey(status),
    reasonStatus: status || 'UNKNOWN'
  }
}

export function getOrderActionSummary(order: OrderLike, t?: TFunction) {
  const policy = getOrderActionPolicy(order)
  if (policy.canCancel && policy.canModify) return t ? t('orders.actionSummary.available') : 'orders.actionSummary.available'
  if (t) return formatOrderActionReason(policy, 'modify', t) ?? t('orders.actionSummary.unavailable')
  return policy.modifyReasonKey ?? policy.cancelReasonKey ?? 'orders.actionSummary.unavailable'
}

export function formatOrderActionReason(policy: OrderActionPolicy, action: 'cancel' | 'modify', t: TFunction) {
  const reasonKey = action === 'cancel' ? policy.cancelReasonKey : policy.modifyReasonKey
  if (!reasonKey) return undefined
  return t(reasonKey, {
    action: t(action === 'cancel' ? 'orders.cancel' : 'orders.modify'),
    status: policy.reasonStatus ?? 'UNKNOWN'
  })
}

function getUnavailableReasonKey(status: string) {
  switch (status) {
    case 'ACCEPTED':
      return 'orders.actionReasons.accepted'
    case 'WORKING':
      return 'orders.actionReasons.working'
    case 'PARTIALLY_FILLED':
      return 'orders.actionReasons.partiallyFilled'
    case 'FILLED':
      return 'orders.actionReasons.filled'
    case 'CANCELED':
    case 'CANCELLED':
      return 'orders.actionReasons.canceled'
    case 'REJECTED':
      return 'orders.actionReasons.rejected'
    case 'CANCEL_PENDING':
      return 'orders.actionReasons.cancelPending'
    default:
      return 'orders.actionReasons.default'
  }
}

function normalizeStatus(status: string) {
  return status.trim().toUpperCase()
}
