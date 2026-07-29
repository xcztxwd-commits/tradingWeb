import {
  createContentPreviewModel,
  type ContentPreviewModel,
  type ContentPreviewSurface
} from '@fx-platform/frontend-core/models'

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu

export const EMPTY_DOCUMENT = Object.freeze({
  type: 'doc',
  content: Object.freeze([Object.freeze({ type: 'paragraph' })])
})

export type PopupCampaignLifecycleStatus =
  | 'DRAFT'
  | 'SCHEDULED'
  | 'ACTIVE'
  | 'PAUSED'
  | 'ENDED'
  | 'DELETED'
export type PopupCampaignAudienceType = 'ALL' | 'SELECTED'
export type PopupCampaignDisplayScope = 'ALL_BUSINESS_PAGES' | 'SELECTED_PAGES'
export type PopupCampaignDeviceScope = 'ALL' | 'PC' | 'MOBILE'
export type PopupCampaignTemplateSize = 'SMALL' | 'MEDIUM' | 'LARGE'
export type PopupCampaignPageKey =
  | 'HOME'
  | 'TRADE_SPOT'
  | 'TRADE_PERPETUAL'
  | 'DASHBOARD'
  | 'MARKETS'
  | 'ORDERS'
  | 'POSITIONS'
  | 'WALLET'
  | 'ACCOUNT_OVERVIEW'
  | 'ACCOUNT_ASSETS'
  | 'FUNDING_RECORDS'
  | 'TRADE_RECORDS'
  | 'KYC'
  | 'ACCOUNT_SETTINGS'
  | 'SECURITY'
  | 'SETTINGS'
  | 'MESSAGES'

export type AdminUserSearchResult = Readonly<{
  id: string
  email?: string
  phone?: string | null
  status?: 'ACTIVE' | 'FROZEN' | 'DISABLED'
}>

export type PopupCampaignCtaForm = Readonly<{
  label: string
  routeKey: string
  params: Readonly<Record<string, string>>
}>

export type PopupCampaignForm = {
  name: string
  title: string
  bodyDocument: Readonly<Record<string, unknown>>
  coverAssetId: string | null
  cta: PopupCampaignCtaForm | null
  audienceType: PopupCampaignAudienceType
  selectedUsers: AdminUserSearchResult[]
  syncToInbox: boolean
  priority: number
  displayScope: PopupCampaignDisplayScope
  pageKeys: PopupCampaignPageKey[]
  deviceScope: PopupCampaignDeviceScope
  templateSize: PopupCampaignTemplateSize
  timeZone: string
  startAt: string
  endAt: string
  maxTotalImpressions: number
  maxDailyImpressions: number
  minIntervalSeconds: number
  reason: string
}

export type PopupCampaignSaveRequest = Readonly<{
  name: string
  content: Readonly<{
    title: string
    bodyDocument: string
    coverAssetId: string | null
    cta: Readonly<{
      label: string
      routeKey: string
      paramsJson: string
    }> | null
  }>
  audienceType: PopupCampaignAudienceType
  targetUserIds: string[]
  syncToInbox: boolean
  priority: number
  displayScope: PopupCampaignDisplayScope
  pageKeys: PopupCampaignPageKey[]
  deviceScope: PopupCampaignDeviceScope
  templateSize: PopupCampaignTemplateSize
  timeZone: string
  startAt: string
  endAt: string
  maxTotalImpressions: number
  maxDailyImpressions: number
  minIntervalSeconds: number
  reason: string
}>

export type PopupCampaignSummary = Readonly<{
  id: string
  name: string
  lifecycleStatus: PopupCampaignLifecycleStatus
  audienceType: PopupCampaignAudienceType
  syncToInbox: boolean
  priority: number
  startAt: string
  endAt: string
  firstPublishedAt: string | null
  revisionId: string
  title: string
  targetCount: number
  updatedAt: string
}>

export type PopupCampaignDetail = Readonly<{
  id: string
  name: string
  lifecycleStatus: PopupCampaignLifecycleStatus
  audienceType: PopupCampaignAudienceType
  targetUserIds: string[]
  syncToInbox: boolean
  priority: number
  displayScope: PopupCampaignDisplayScope
  pageKeys: PopupCampaignPageKey[]
  deviceScope: PopupCampaignDeviceScope
  templateSize: PopupCampaignTemplateSize
  timeZone: string
  startAt: string
  endAt: string
  maxTotalImpressions: number
  maxDailyImpressions: number
  minIntervalSeconds: number
  firstPublishedAt: string | null
  lastPublishedAt: string | null
  pausedAt: string | null
  endedAt: string | null
  deletedAt: string | null
  contentItemId: string
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

export type PopupCampaignStats = Readonly<{
  campaignId: string
  targetCount: number
  usersWithState: number
  totalImpressions: number
  optedOutUsers: number
  clickedUsers: number
  issuedDeliveries: number
  shownDeliveries: number
  closedDeliveries: number
  clickedDeliveries: number
  invalidatedDeliveries: number
  expiredDeliveries: number
}>

export type PopupCampaignUserDetail = Readonly<{
  userId: string
  email: string
  status: 'ACTIVE' | 'FROZEN' | 'DISABLED'
  totalImpressions: number
  dailyBucket: string | null
  dailyImpressions: number
  lastImpressionAt: string | null
  optedOutAt: string | null
  lastClickedAt: string | null
  issuedDeliveries: number
  shownDeliveries: number
  closedDeliveries: number
  clickedDeliveries: number
  invalidatedDeliveries: number
  expiredDeliveries: number
}>

export type PopupPolicy = Readonly<{
  maxSequentialPopups: number
  deliveryRetentionDays: number
}>

export type PopupPolicyUpdateRequest = PopupPolicy & Readonly<{ reason: string }>

export type ContentAssetUploadResult = Readonly<{
  assetId: string
  url: string
  mimeType: 'image/jpeg' | 'image/png' | 'image/webp'
  byteSize: number
  width: number
  height: number
}>

export const campaignFrozenFields = Object.freeze([
  'name',
  'audienceType',
  'targetUserIds',
  'syncToInbox',
  'startAt',
  'timeZone',
  'templateSize'
] as const)

type FrozenField = typeof campaignFrozenFields[number]
type FormField = keyof PopupCampaignForm

export function isCampaignFieldFrozen(
  campaign: Pick<PopupCampaignDetail, 'firstPublishedAt'>,
  field: FormField | FrozenField
) {
  const backendField = field === 'selectedUsers' ? 'targetUserIds' : field
  return Boolean(campaign.firstPublishedAt) && campaignFrozenFields.includes(backendField as FrozenField)
}

export function createPopupCampaignForm(
  campaign?: PopupCampaignDetail,
  now = new Date()
): PopupCampaignForm {
  if (campaign) {
    return {
      name: campaign.name,
      title: campaign.title,
      bodyDocument: parseObject(campaign.bodyDocument, 'bodyDocument'),
      coverAssetId: campaign.coverAssetId,
      cta: campaign.ctaLabel && campaign.ctaRouteKey && campaign.ctaParams
        ? {
            label: campaign.ctaLabel,
            routeKey: campaign.ctaRouteKey,
            params: parseStringMap(campaign.ctaParams, 'ctaParams')
          }
        : null,
      audienceType: campaign.audienceType,
      selectedUsers: campaign.targetUserIds.map((id) => ({ id })),
      syncToInbox: campaign.syncToInbox,
      priority: campaign.priority,
      displayScope: campaign.displayScope,
      pageKeys: [...campaign.pageKeys],
      deviceScope: campaign.deviceScope,
      templateSize: campaign.templateSize,
      timeZone: campaign.timeZone,
      startAt: campaign.startAt,
      endAt: campaign.endAt,
      maxTotalImpressions: campaign.maxTotalImpressions,
      maxDailyImpressions: campaign.maxDailyImpressions,
      minIntervalSeconds: campaign.minIntervalSeconds,
      reason: ''
    }
  }

  const startAt = new Date(now)
  const endAt = new Date(startAt.getTime() + 7 * 24 * 60 * 60 * 1000)
  return {
    name: '',
    title: '',
    bodyDocument: { type: 'doc', content: [{ type: 'paragraph' }] },
    coverAssetId: null,
    cta: null,
    audienceType: 'ALL',
    selectedUsers: [],
    syncToInbox: false,
    priority: 0,
    displayScope: 'ALL_BUSINESS_PAGES',
    pageKeys: [],
    deviceScope: 'ALL',
    templateSize: 'MEDIUM',
    timeZone: 'Asia/Shanghai',
    startAt: startAt.toISOString(),
    endAt: endAt.toISOString(),
    maxTotalImpressions: 3,
    maxDailyImpressions: 1,
    minIntervalSeconds: 4 * 60 * 60,
    reason: ''
  }
}

export function normalizeCampaignSaveRequest(form: PopupCampaignForm): PopupCampaignSaveRequest {
  const errors = validateConfiguration(form)
  if (errors.length) throw new TypeError(errors.join('; '))

  const targetUserIds = form.audienceType === 'SELECTED'
    ? [...new Set(form.selectedUsers.map(({ id }) => requireUuid(id, 'selected user ID')))]
    : []
  const pageKeys = form.displayScope === 'SELECTED_PAGES' ? [...new Set(form.pageKeys)] : []

  return {
    name: form.name.trim(),
    content: {
      title: form.title.trim(),
      bodyDocument: JSON.stringify(form.bodyDocument),
      coverAssetId: form.coverAssetId,
      cta: form.cta
        ? {
            label: form.cta.label.trim(),
            routeKey: form.cta.routeKey,
            paramsJson: JSON.stringify(form.cta.params)
          }
        : null
    },
    audienceType: form.audienceType,
    targetUserIds,
    syncToInbox: form.syncToInbox,
    priority: form.priority,
    displayScope: form.displayScope,
    pageKeys,
    deviceScope: form.deviceScope,
    templateSize: form.templateSize,
    timeZone: form.timeZone,
    startAt: form.startAt,
    endAt: form.endAt,
    maxTotalImpressions: form.maxTotalImpressions,
    maxDailyImpressions: form.maxDailyImpressions,
    minIntervalSeconds: form.minIntervalSeconds,
    reason: form.reason.trim()
  }
}

export type PopupCampaignPublicationMode = 'IMMEDIATE' | 'SCHEDULED'

export function validateCampaignPublication(
  form: PopupCampaignForm,
  mode: PopupCampaignPublicationMode,
  now = new Date()
) {
  const errors = validateConfiguration(form)
  const startAt = instant(form.startAt)
  const endAt = instant(form.endAt)
  if (startAt !== null && mode === 'SCHEDULED' && startAt <= now.getTime()) {
    errors.push('startAt must be in the future for scheduled publication')
  }
  if (startAt !== null && mode === 'IMMEDIATE' && startAt > now.getTime()) {
    errors.push('startAt must not be in the future for immediate publication')
  }
  if (endAt !== null && endAt <= now.getTime()) {
    errors.push('endAt must be in the future')
  }
  if (form.audienceType === 'SELECTED' && form.selectedUsers.length === 0) {
    errors.push('selected users are required')
  }
  return [...new Set(errors)]
}

export type PopupCampaignAction =
  | 'edit'
  | 'test-popup'
  | 'publish'
  | 'pause'
  | 'resume'
  | 'end'
  | 'reset-delivery'
  | 'delete'
  | 'restore'
  | 'stats'
  | 'users'

export function campaignActions(
  campaign: Pick<PopupCampaignSummary, 'lifecycleStatus' | 'firstPublishedAt'>,
  authorities: ReadonlySet<string> | readonly string[]
): PopupCampaignAction[] {
  const allowed = authorities instanceof Set ? authorities : new Set(authorities)
  const actions: PopupCampaignAction[] = []
  const deleted = campaign.lifecycleStatus === 'DELETED'

  if (!deleted && allowed.has('content:campaign:edit')) actions.push('edit', 'test-popup')
  if (allowed.has('content:campaign:publish') && !deleted) {
    if (campaign.lifecycleStatus === 'DRAFT'
      || (campaign.lifecycleStatus === 'PAUSED' && !campaign.firstPublishedAt)) {
      actions.push('publish')
    } else if (campaign.lifecycleStatus === 'ACTIVE' || campaign.lifecycleStatus === 'SCHEDULED') {
      actions.push('pause', 'end')
    } else if (campaign.lifecycleStatus === 'PAUSED') {
      actions.push('resume', 'end')
    }
    if (campaign.firstPublishedAt) actions.push('reset-delivery')
  }
  if (allowed.has('content:campaign:delete')) actions.push(deleted ? 'restore' : 'delete')
  if (allowed.has('content:campaign:stats')) actions.push('stats')
  if (allowed.has('content:campaign:user-detail')) actions.push('users')
  return actions
}

type CampaignPreviewSource = Pick<
  PopupCampaignDetail,
  'templateSize' | 'title' | 'sanitizedHtml' | 'coverAssetId'
  | 'ctaLabel' | 'ctaRouteKey' | 'ctaParams'
>

export type CampaignPreviewModel = ContentPreviewModel & Readonly<{
  preview: true
  previewLabel: 'PREVIEW'
}>

export function createCampaignPreview(
  campaign: CampaignPreviewSource,
  surface: ContentPreviewSurface
): CampaignPreviewModel {
  const hasCta = campaign.ctaLabel !== null
    || campaign.ctaRouteKey !== null
    || campaign.ctaParams !== null
  const cta = hasCta
    ? {
        label: requiredText(campaign.ctaLabel, 'ctaLabel'),
        routeKey: requiredText(campaign.ctaRouteKey, 'ctaRouteKey'),
        params: parseStringMap(campaign.ctaParams, 'ctaParams')
      }
    : null
  const preview = createContentPreviewModel({
    sizeMode: campaign.templateSize,
    title: campaign.title,
    sanitizedHtml: campaign.sanitizedHtml,
    coverAssetId: campaign.coverAssetId,
    cta
  }, surface)
  return Object.freeze({ ...preview, preview: true, previewLabel: 'PREVIEW' })
}

function validateConfiguration(form: PopupCampaignForm) {
  const errors: string[] = []
  if (!form.name?.trim()) errors.push('name is required')
  if (!form.title?.trim()) errors.push('title is required')
  if (!form.reason?.trim()) errors.push('reason is required')
  if (form.reason?.trim().length > 500) errors.push('reason is too long')
  if (!isTimeZone(form.timeZone)) errors.push('timeZone is invalid')

  const startAt = instant(form.startAt)
  const endAt = instant(form.endAt)
  if (startAt === null) errors.push('startAt is invalid')
  if (endAt === null) errors.push('endAt is invalid')
  if (startAt !== null && endAt !== null && endAt <= startAt) {
    errors.push('endAt must be after startAt')
  }
  if (!Number.isInteger(form.maxTotalImpressions) || form.maxTotalImpressions < 1) {
    errors.push('maxTotalImpressions must be positive')
  }
  if (!Number.isInteger(form.maxDailyImpressions)
    || form.maxDailyImpressions < 1
    || form.maxDailyImpressions > form.maxTotalImpressions) {
    errors.push('maxDailyImpressions must not exceed maxTotalImpressions')
  }
  if (!Number.isInteger(form.minIntervalSeconds) || form.minIntervalSeconds < 0) {
    errors.push('minIntervalSeconds must not be negative')
  }
  if (form.displayScope === 'SELECTED_PAGES' && form.pageKeys.length === 0) {
    errors.push('selected pages are required')
  }
  if (form.coverAssetId !== null && !UUID_PATTERN.test(form.coverAssetId)) {
    errors.push('coverAssetId must be a UUID')
  }
  if (form.cta && (!form.cta.label.trim() || !form.cta.routeKey.trim())) {
    errors.push('CTA label and route are required')
  }
  return errors
}

function instant(value: string) {
  if (typeof value !== 'string'
    || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/u.test(value)) {
    return null
  }
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

function isTimeZone(value: string) {
  if (!value?.trim()) return false
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: value }).format()
    return true
  } catch {
    return false
  }
}

function requireUuid(value: string, name: string) {
  if (!UUID_PATTERN.test(value)) throw new TypeError(`${name} must be a UUID`)
  return value
}

function requiredText(value: string | null, name: string) {
  if (!value?.trim()) throw new TypeError(`${name} is required`)
  return value
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
  for (const item of Object.values(parsed)) {
    if (typeof item !== 'string') throw new TypeError(`${name} values must be text`)
  }
  return parsed as Readonly<Record<string, string>>
}
