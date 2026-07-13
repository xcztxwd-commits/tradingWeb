import type { TFunction } from 'i18next'
import type { OrderOrigin, ProductType, ProtectionType } from '@fx-platform/shared-types'

type OrderLike = {
  status: string
  productType?: ProductType | null
  orderType?: string | null
  triggerExecutionType?: string | null
  origin?: OrderOrigin | null
  orderOrigin?: OrderOrigin | null
  protectionType?: ProtectionType | null
  parentPositionId?: string | null
  contingencyGroupId?: string | null
  version?: number | null
}

type OrderActionPolicy = {
  canCancel: boolean
  canModify: boolean
  cancelVia: 'ORDER' | 'PROTECTION' | null
  modifyVia: 'ORDER' | 'PROTECTION' | null
  cancelReasonKey?: string
  modifyReasonKey?: string
  reasonStatus?: string
}

const currentOrderStatuses = new Set([
  'RECEIVED',
  'VALIDATING',
  'PENDING',
  'PENDING_ACTIVATION',
  'ACCEPTED',
  'WORKING',
  'PARTIALLY_FILLED',
  'CANCEL_PENDING'
])

export function isCurrentOrderStatus(status: string) {
  return currentOrderStatuses.has(normalizeStatus(status))
}

export function getOrderActionPolicy(order: OrderLike): OrderActionPolicy {
  const status = normalizeStatus(order.status)
  const origin = normalizeStatus(order.orderOrigin ?? order.origin ?? '')
  const isProtection = Boolean(order.protectionType) || origin === 'PROTECTIVE'

  if (isProtection) {
    if (!order.parentPositionId) {
      return unavailablePolicy(status, 'orders.actionReasons.protectionUnavailable')
    }
    const isTriggeredRestingLimit = status === 'PENDING'
      && normalizeStatus(order.orderType ?? '') === 'LIMIT'
      && normalizeStatus(order.triggerExecutionType ?? '') === 'LIMIT'
    if (isTriggeredRestingLimit) {
      return {
        canCancel: true,
        canModify: false,
        cancelVia: 'PROTECTION',
        modifyVia: null,
        modifyReasonKey: 'orders.actionReasons.protectionUnavailable',
        reasonStatus: status
      }
    }
    if (status !== 'PENDING_ACTIVATION') {
      return unavailablePolicy(status, 'orders.actionReasons.protectionUnavailable')
    }

    const hasVersion = Number.isSafeInteger(order.version) && Number(order.version) >= 0
    return {
      canCancel: true,
      canModify: hasVersion,
      cancelVia: 'PROTECTION',
      modifyVia: hasVersion ? 'PROTECTION' : null,
      ...(hasVersion ? {} : { modifyReasonKey: 'orders.actionReasons.versionUnavailable' }),
      reasonStatus: status
    }
  }

  if (status === 'PENDING') {
    const isOco = origin === 'OCO' || Boolean(order.contingencyGroupId)
    const isSpotModifiable = order.productType === 'CRYPTO_SPOT'
      && ['LIMIT', 'STOP_MARKET'].includes(normalizeStatus(order.orderType ?? ''))
    const canModify = isSpotModifiable && !isOco
    return {
      canCancel: true,
      canModify,
      cancelVia: 'ORDER',
      modifyVia: canModify ? 'ORDER' : null,
      ...(canModify ? {} : {
        modifyReasonKey: isOco
          ? 'orders.actionReasons.ocoNotModifiable'
          : 'orders.actionReasons.productNotModifiable'
      }),
      reasonStatus: status
    }
  }

  return unavailablePolicy(status)
}

function unavailablePolicy(status: string, reasonKey = getUnavailableReasonKey(status)): OrderActionPolicy {
  return {
    canCancel: false,
    canModify: false,
    cancelVia: null,
    modifyVia: null,
    cancelReasonKey: reasonKey,
    modifyReasonKey: reasonKey,
    reasonStatus: status || 'UNKNOWN'
  }
}

export function getOrderActionSummary(order: OrderLike, t?: TFunction) {
  const policy = getOrderActionPolicy(order)
  if (policy.canCancel && policy.canModify) return t ? t('orders.actionSummary.available') : 'orders.actionSummary.available'
  if (policy.canCancel) return t ? t('orders.actionSummary.cancelOnly') : 'orders.actionSummary.cancelOnly'
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
