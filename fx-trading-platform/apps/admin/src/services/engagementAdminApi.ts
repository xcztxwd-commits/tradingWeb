import type { AdminPage } from '../types.ts'
import type {
  AdminUserSearchResult,
  ContentAssetUploadResult,
  PopupCampaignAudienceType,
  PopupCampaignDetail,
  PopupCampaignLifecycleStatus,
  PopupCampaignSaveRequest,
  PopupCampaignStats,
  PopupCampaignSummary,
  PopupCampaignUserDetail,
  PopupPolicy,
  PopupPolicyUpdateRequest
} from '../pages/popupCampaignModel.ts'
import type {
  NormalMessageDetail,
  NormalMessageLifecycleStatus,
  NormalMessageSaveRequest,
  NormalMessageSendRequest,
  NormalMessageSummary,
  NormalMessageUpdateRequest
} from '../pages/normalMessageModel.ts'
import { apiDelete, apiGet, apiPost, apiPostMultipart, apiPut } from './apiClient.ts'

const CAMPAIGNS_PATH = '/api/admin/engagement/campaigns'
const MESSAGES_PATH = '/api/admin/engagement/messages'

export type PopupCampaignListQuery = Readonly<{
  page?: number
  size?: number
  lifecycleStatus?: PopupCampaignLifecycleStatus
  name?: string
  audienceType?: PopupCampaignAudienceType
  syncToInbox?: boolean
  effectiveFrom?: string
  effectiveTo?: string
}>

export function getPopupCampaigns(token: string, query: PopupCampaignListQuery = {}) {
  const params = new URLSearchParams()
  params.set('page', String(query.page ?? 0))
  params.set('size', String(query.size ?? 20))
  append(params, 'lifecycleStatus', query.lifecycleStatus)
  append(params, 'name', query.name)
  append(params, 'audienceType', query.audienceType)
  if (query.syncToInbox !== undefined) params.set('syncToInbox', String(query.syncToInbox))
  append(params, 'effectiveFrom', query.effectiveFrom)
  append(params, 'effectiveTo', query.effectiveTo)
  return apiGet<AdminPage<PopupCampaignSummary>>(`${CAMPAIGNS_PATH}?${params}`, token)
}

export function getPopupCampaign(token: string, campaignId: string) {
  return apiGet<PopupCampaignDetail>(campaignPath(campaignId), token)
}

export function createPopupCampaign(token: string, request: PopupCampaignSaveRequest) {
  return apiPost<PopupCampaignDetail>(CAMPAIGNS_PATH, request, token)
}

export function updatePopupCampaign(
  token: string,
  campaignId: string,
  request: PopupCampaignSaveRequest
) {
  return apiPut<PopupCampaignDetail>(campaignPath(campaignId), request, token)
}

export type PopupCampaignApiAction =
  | 'publish'
  | 'pause'
  | 'resume'
  | 'end'
  | 'delete'
  | 'restore'
  | 'reset-delivery'
  | 'test-popup'

export type PopupCampaignPreviewResponse = Readonly<{
  preview: boolean
  campaignId: string
  revisionId: string
  templateSize: 'SMALL' | 'MEDIUM' | 'LARGE'
  title: string
  sanitizedHtml: string
  coverAssetId: string | null
  ctaLabel: string | null
  ctaRouteKey: string | null
  ctaParams: string | null
}>

export type PopupCampaignResetResponse = Readonly<{ affectedUsers: number }>

type PopupCampaignActionResult = {
  publish: PopupCampaignDetail
  pause: PopupCampaignDetail
  resume: PopupCampaignDetail
  end: PopupCampaignDetail
  delete: PopupCampaignDetail
  restore: PopupCampaignDetail
  'reset-delivery': PopupCampaignResetResponse
  'test-popup': PopupCampaignPreviewResponse
}

const POPUP_CAMPAIGN_ACTION_PATHS: Record<Exclude<PopupCampaignApiAction, 'delete'>, string> = {
  publish: '/publish',
  pause: '/pause',
  resume: '/resume',
  end: '/end',
  restore: '/restore',
  'reset-delivery': '/reset-delivery',
  'test-popup': '/test-popup'
}

export function runPopupCampaignAction<Action extends PopupCampaignApiAction>(
  token: string,
  campaignId: string,
  action: Action,
  reason: string
): Promise<PopupCampaignActionResult[Action]> {
  const body = { reason: requiredReason(reason) }
  if (action === 'delete') {
    return apiDelete<PopupCampaignActionResult[Action]>(campaignPath(campaignId), body, token)
  }
  return apiPost<PopupCampaignActionResult[Action]>(
    `${campaignPath(campaignId)}${POPUP_CAMPAIGN_ACTION_PATHS[
      action as Exclude<PopupCampaignApiAction, 'delete'>
    ]}`,
    body,
    token
  )
}

export function getPopupCampaignStats(token: string, campaignId: string) {
  return apiGet<PopupCampaignStats>(`${campaignPath(campaignId)}/stats`, token)
}

export type PopupCampaignUsersQuery = Readonly<{
  page?: number
  size?: number
  reason: string
}>

export function getPopupCampaignUsers(
  token: string,
  campaignId: string,
  query: PopupCampaignUsersQuery
) {
  const params = new URLSearchParams({
    page: String(query.page ?? 0),
    size: String(query.size ?? 20),
    reason: requiredReason(query.reason)
  })
  return apiGet<AdminPage<PopupCampaignUserDetail>>(
    `${campaignPath(campaignId)}/users?${params}`,
    token
  )
}

export function getPopupPolicy(token: string) {
  return apiGet<PopupPolicy>('/api/admin/engagement/popup-policy', token)
}

export function updatePopupPolicy(token: string, request: PopupPolicyUpdateRequest) {
  return apiPut<PopupPolicy>('/api/admin/engagement/popup-policy', request, token)
}

export type AdminUserSearchQuery = Readonly<{
  q?: string
  page?: number
  size?: number
}>

export function searchAdminUsers(token: string, query: AdminUserSearchQuery = {}) {
  const params = new URLSearchParams()
  append(params, 'q', query.q)
  params.set('page', String(query.page ?? 0))
  params.set('size', String(query.size ?? 20))
  return apiGet<AdminPage<AdminUserSearchResult>>(`/api/admin/users/search?${params}`, token)
}

export function uploadContentAsset(
  token: string,
  file: Blob,
  fileName = typeof File !== 'undefined' && file instanceof File ? file.name : 'asset'
) {
  const body = new FormData()
  body.append('file', file, fileName)
  return apiPostMultipart<ContentAssetUploadResult>('/api/admin/engagement/assets', body, token)
}

export type NormalMessageListQuery = Readonly<{
  page?: number
  size?: number
  lifecycleStatus?: NormalMessageLifecycleStatus
  title?: string
}>

export function getNormalMessages(token: string, query: NormalMessageListQuery = {}) {
  const params = new URLSearchParams()
  params.set('page', String(query.page ?? 0))
  params.set('size', String(query.size ?? 20))
  append(params, 'lifecycleStatus', query.lifecycleStatus)
  append(params, 'title', query.title)
  return apiGet<AdminPage<NormalMessageSummary>>(`${MESSAGES_PATH}?${params}`, token)
}

export function getNormalMessage(token: string, messageId: string) {
  return apiGet<NormalMessageDetail>(messagePath(messageId), token)
}

export function createNormalMessage(token: string, request: NormalMessageSaveRequest) {
  return apiPost<NormalMessageDetail>(MESSAGES_PATH, request, token)
}

export function updateNormalMessage(
  token: string,
  messageId: string,
  request: NormalMessageUpdateRequest
) {
  return apiPut<NormalMessageDetail>(messagePath(messageId), request, token)
}

export function sendNormalMessage(
  token: string,
  messageId: string,
  request: NormalMessageSendRequest
) {
  return apiPost<NormalMessageDetail>(`${messagePath(messageId)}/send`, {
    sendAt: request.sendAt,
    reason: requiredReason(request.reason)
  }, token)
}

export function cancelNormalMessageSchedule(token: string, messageId: string, reason: string) {
  return normalMessagePostAction(token, messageId, 'cancel-schedule', reason)
}

export function deleteNormalMessage(token: string, messageId: string, reason: string) {
  return apiDelete<NormalMessageDetail>(
    messagePath(messageId),
    { reason: requiredReason(reason) },
    token
  )
}

export function restoreNormalMessage(token: string, messageId: string, reason: string) {
  return normalMessagePostAction(token, messageId, 'restore', reason)
}

function campaignPath(campaignId: string) {
  return `${CAMPAIGNS_PATH}/${encodeURIComponent(campaignId)}`
}

function messagePath(messageId: string) {
  return `${MESSAGES_PATH}/${encodeURIComponent(messageId)}`
}

function normalMessagePostAction(
  token: string,
  messageId: string,
  action: 'cancel-schedule' | 'restore',
  reason: string
) {
  return apiPost<NormalMessageDetail>(
    `${messagePath(messageId)}/${action}`,
    { reason: requiredReason(reason) },
    token
  )
}

function append(params: URLSearchParams, key: string, value: string | undefined) {
  if (value?.trim()) params.set(key, value.trim())
}

function requiredReason(reason: string) {
  const value = reason?.trim()
  if (!value) throw new TypeError('reason is required')
  if (value.length > 500) throw new TypeError('reason is too long')
  return value
}
