import {
  EMPTY_DOCUMENT,
  UUID_PATTERN,
  type AdminUserSearchResult
} from './popupCampaignModel.ts'

export const NORMAL_MESSAGE_DELIVERY_NOTE = '普通消息仅更新消息中心和未读数，不会自动弹窗。'

export type NormalMessageLifecycleStatus = 'DRAFT' | 'SCHEDULED' | 'SENT' | 'DELETED'
export type NormalMessageAudienceType = 'ALL' | 'SELECTED'
export type NormalMessageSendMode = 'IMMEDIATE' | 'SCHEDULED'

export type NormalMessageCtaForm = Readonly<{
  label: string
  routeKey: string
  params: Readonly<Record<string, string>>
}>

export type NormalMessageForm = {
  category: string
  title: string
  bodyDocument: Readonly<Record<string, unknown>>
  coverAssetId: string | null
  cta: NormalMessageCtaForm | null
  audienceType: NormalMessageAudienceType
  selectedUsers: AdminUserSearchResult[]
  reason: string
}

export type NormalMessageContentRequest = Readonly<{
  title: string
  bodyDocument: string
  coverAssetId: string | null
  cta: Readonly<{
    label: string
    routeKey: string
    paramsJson: string
  }> | null
}>

export type NormalMessageSaveRequest = Readonly<{
  category: string
  audienceType: NormalMessageAudienceType
  targetUserIds: string[]
  content: NormalMessageContentRequest
  reason: string
}>

export type NormalMessageUpdateRequest = Readonly<{
  audienceType?: NormalMessageAudienceType
  targetUserIds?: string[]
  content: NormalMessageContentRequest
  reason: string
}>

export type NormalMessageSendRequest = Readonly<{
  sendAt: string | null
  reason: string
}>

export type NormalMessageSummary = Readonly<{
  id: string
  lifecycleStatus: NormalMessageLifecycleStatus
  audienceType: NormalMessageAudienceType
  category: string
  scheduledAt: string | null
  sentAt: string | null
  revisionId: string
  title: string
  targetCount: number
  updatedAt: string
}>

export type NormalMessageDetail = Readonly<{
  id: string
  contentItemId: string
  lifecycleStatus: NormalMessageLifecycleStatus
  audienceType: NormalMessageAudienceType
  category: string
  targetUserIds: string[]
  scheduledAt: string | null
  sentAt: string | null
  audienceCutoffAt: string | null
  deletedAt: string | null
  revisionId: string
  revisionNo: number
  title: string
  bodyDocument: string
  sanitizedHtml: string
  coverAssetId: string | null
  ctaLabel: string | null
  ctaRouteKey: string | null
  ctaParams: string | null
  targetCount: number
  createdAt: string
  updatedAt: string
}>

export function createNormalMessageForm(message?: NormalMessageDetail): NormalMessageForm {
  if (!message) {
    return {
      category: 'NOTICE',
      title: '',
      bodyDocument: EMPTY_DOCUMENT,
      coverAssetId: null,
      cta: null,
      audienceType: 'ALL',
      selectedUsers: [],
      reason: ''
    }
  }

  const hasCta = message.ctaLabel !== null
    || message.ctaRouteKey !== null
    || message.ctaParams !== null
  return {
    category: message.category,
    title: message.title,
    bodyDocument: parseObject(message.bodyDocument, 'bodyDocument'),
    coverAssetId: message.coverAssetId,
    cta: hasCta
      ? {
          label: requiredText(message.ctaLabel, 'ctaLabel'),
          routeKey: requiredText(message.ctaRouteKey, 'ctaRouteKey'),
          params: parseStringMap(message.ctaParams, 'ctaParams')
        }
      : null,
    audienceType: message.audienceType,
    selectedUsers: message.targetUserIds.map((id) => ({ id })),
    reason: ''
  }
}

export function normalizeNormalMessageSaveRequest(
  form: NormalMessageForm
): NormalMessageSaveRequest {
  const category = requiredText(form.category, 'category')
  if (category.length > 64) throw new TypeError('category is too long')
  return {
    category,
    audienceType: form.audienceType,
    targetUserIds: targetIds(form),
    content: normalizeContent(form),
    reason: reason(form.reason)
  }
}

export function normalizeNormalMessageUpdateRequest(
  form: NormalMessageForm,
  lifecycleStatus: NormalMessageLifecycleStatus
): NormalMessageUpdateRequest {
  if (!isNormalMessageContentEditable(lifecycleStatus)) {
    throw new TypeError('deleted message content cannot be edited')
  }
  const request = {
    content: normalizeContent(form),
    reason: reason(form.reason)
  }
  return isNormalMessageAudienceFrozen(lifecycleStatus)
    ? request
    : { ...request, audienceType: form.audienceType, targetUserIds: targetIds(form) }
}

export function normalizeNormalMessageSendRequest(
  form: NormalMessageForm,
  mode: NormalMessageSendMode,
  sendAt: string | null,
  now = new Date()
): NormalMessageSendRequest {
  if (form.audienceType === 'SELECTED' && targetIds(form).length === 0) {
    throw new TypeError('selected users are required')
  }
  const requestReason = reason(form.reason)
  if (mode === 'IMMEDIATE') return { sendAt: null, reason: requestReason }
  const scheduledAt = instant(sendAt)
  if (scheduledAt === null || scheduledAt <= now.getTime()) {
    throw new TypeError('sendAt must be a valid future instant')
  }
  return { sendAt, reason: requestReason }
}

export function isNormalMessageAudienceFrozen(lifecycleStatus: NormalMessageLifecycleStatus) {
  return lifecycleStatus === 'SENT'
}

export function isNormalMessageContentEditable(lifecycleStatus: NormalMessageLifecycleStatus) {
  return lifecycleStatus !== 'DELETED'
}

export type NormalMessageAction =
  | 'edit'
  | 'send-now'
  | 'schedule'
  | 'cancel-schedule'
  | 'delete'
  | 'restore'

export function normalMessageActions(
  lifecycleStatus: NormalMessageLifecycleStatus,
  authorities: ReadonlySet<string> | readonly string[]
): NormalMessageAction[] {
  const allowed = authorities instanceof Set ? authorities : new Set(authorities)
  if (lifecycleStatus === 'DELETED') {
    return allowed.has('content:message:delete') ? ['restore'] : []
  }

  const actions: NormalMessageAction[] = []
  if (allowed.has('content:message:edit')) actions.push('edit')
  if (allowed.has('content:message:send') && lifecycleStatus !== 'SENT') {
    actions.push('send-now', 'schedule')
    if (lifecycleStatus === 'SCHEDULED') actions.push('cancel-schedule')
  }
  if (allowed.has('content:message:delete')) actions.push('delete')
  return actions
}

function normalizeContent(form: NormalMessageForm): NormalMessageContentRequest {
  const title = requiredText(form.title, 'title')
  if (title.length > 200) throw new TypeError('title is too long')
  if (!form.bodyDocument || typeof form.bodyDocument !== 'object' || Array.isArray(form.bodyDocument)) {
    throw new TypeError('bodyDocument must be an object')
  }
  if (form.coverAssetId !== null && !UUID_PATTERN.test(form.coverAssetId)) {
    throw new TypeError('coverAssetId must be a UUID')
  }
  return {
    title,
    bodyDocument: JSON.stringify(form.bodyDocument),
    coverAssetId: form.coverAssetId,
    cta: form.cta
      ? {
          label: requiredText(form.cta.label, 'CTA label'),
          routeKey: requiredText(form.cta.routeKey, 'CTA route'),
          paramsJson: JSON.stringify(form.cta.params)
        }
      : null
  }
}

function targetIds(form: NormalMessageForm) {
  return form.audienceType === 'SELECTED'
    ? [...new Set(form.selectedUsers.map(({ id }) => uuid(id, 'selected user ID')))]
    : []
}

function reason(value: string) {
  const result = requiredText(value, 'reason')
  if (result.length > 500) throw new TypeError('reason is too long')
  return result
}

function requiredText(value: string | null, name: string) {
  const result = value?.trim()
  if (!result) throw new TypeError(`${name} is required`)
  return result
}

function uuid(value: string, name: string) {
  if (!UUID_PATTERN.test(value)) throw new TypeError(`${name} must be a UUID`)
  return value
}

function instant(value: string | null) {
  if (!value
    || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/u.test(value)) {
    return null
  }
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

function parseObject(value: string, name: string): Readonly<Record<string, unknown>> {
  let parsed: unknown
  try {
    parsed = JSON.parse(value)
  } catch {
    throw new TypeError(`${name} must be valid JSON`)
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new TypeError(`${name} must be an object`)
  }
  return parsed as Readonly<Record<string, unknown>>
}

function parseStringMap(value: string | null, name: string) {
  const parsed = parseObject(requiredText(value, name), name)
  if (Object.values(parsed).some((item) => typeof item !== 'string')) {
    throw new TypeError(`${name} values must be text`)
  }
  return parsed as Readonly<Record<string, string>>
}
