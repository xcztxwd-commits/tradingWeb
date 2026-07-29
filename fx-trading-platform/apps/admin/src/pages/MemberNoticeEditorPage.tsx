import { lazy, Suspense, useEffect, useMemo, useState, type ChangeEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import type { RestrictedRichTextDocument } from '../components/RestrictedRichTextEditor'
import {
  cancelNormalMessageSchedule,
  createNormalMessage,
  getNormalMessage,
  searchAdminUsers,
  sendNormalMessage,
  updateNormalMessage,
  uploadContentAsset
} from '../services/engagementAdminApi'
import { getAdminAuthorities, getValidAdminToken } from '../services/adminToken'
import type { AdminUserSearchResult, ContentAssetUploadResult } from './popupCampaignModel'
import {
  createNormalMessageForm,
  isNormalMessageAudienceFrozen,
  normalizeNormalMessageSaveRequest,
  normalizeNormalMessageSendRequest,
  normalizeNormalMessageUpdateRequest,
  type NormalMessageDetail,
  type NormalMessageForm,
  type NormalMessageSendMode
} from './normalMessageModel'
import './MemberNotice.css'

const RestrictedRichTextEditor = lazy(() => import('../components/RestrictedRichTextEditor'))

const INTERNAL_ROUTES = [
  'HOME',
  'DASHBOARD',
  'MARKETS',
  'ORDERS',
  'POSITIONS',
  'WALLET',
  'ACCOUNT_OVERVIEW',
  'ACCOUNT_ASSETS',
  'FUNDING_RECORDS',
  'TRADE_RECORDS',
  'KYC',
  'ACCOUNT_SETTINGS',
  'SECURITY',
  'SETTINGS',
  'TRADE_SPOT',
  'TRADE_PERPETUAL',
  'MESSAGE_CENTER'
] as const

export function MemberNoticeEditorPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [form, setForm] = useState<NormalMessageForm>(() => createNormalMessageForm())
  const [notice, setNotice] = useState<NormalMessageDetail>()
  const [sendAt, setSendAt] = useState('')
  const [loading, setLoading] = useState(Boolean(id))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [userQuery, setUserQuery] = useState('')
  const [userResults, setUserResults] = useState<AdminUserSearchResult[]>([])
  const [searchingUsers, setSearchingUsers] = useState(false)
  const [bodyAsset, setBodyAsset] = useState<ContentAssetUploadResult>()
  const [bodyAlt, setBodyAlt] = useState('')
  const [bodyLinkRoute, setBodyLinkRoute] = useState<(typeof INTERNAL_ROUTES)[number]>('MESSAGE_CENTER')
  const [bodyLinkParamsText, setBodyLinkParamsText] = useState('{}')

  const authorities = useMemo(() => new Set(getAdminAuthorities()), [])
  const deleted = notice?.lifecycleStatus === 'DELETED'
  const canEdit = authorities.has('content:message:edit') && !deleted
  const canSend = authorities.has('content:message:send')
    && (Boolean(notice) || canEdit)
    && notice?.lifecycleStatus !== 'SENT'
    && !deleted
  const audienceFrozen = isNormalMessageAudienceFrozen(notice?.lifecycleStatus ?? 'DRAFT')
  const selectedUserIds = useMemo(
    () => new Set(form.selectedUsers.map(({ id: userId }) => userId)),
    [form.selectedUsers]
  )
  const audienceSelection = useMemo(
    () => ({ targetUserIds: Array.from(selectedUserIds) }),
    [selectedUserIds]
  )

  useEffect(() => {
    if (id && notice?.id === id) {
      setLoading(false)
      return
    }
    setError('')
    setMessage('')
    setUserQuery('')
    setUserResults([])
    setBodyAsset(undefined)
    setBodyAlt('')
    setBodyLinkRoute('MESSAGE_CENTER')
    setBodyLinkParamsText('{}')

    if (!id) {
      setNotice(undefined)
      setForm(createNormalMessageForm())
      setSendAt('')
      setLoading(false)
      return
    }
    setNotice(undefined)
    setForm(createNormalMessageForm())
    setSendAt('')
    setLoading(true)
    const token = getValidAdminToken()
    if (!token) {
      setError('登录状态已失效，请重新登录')
      setLoading(false)
      return
    }
    let active = true
    void getNormalMessage(token, id)
      .then((detail) => {
        if (!active) return
        applyNotice(detail, createNormalMessageForm(detail), setNotice, setForm)
        setSendAt(toLocalDateTime(detail.scheduledAt))
      })
      .catch((cause) => {
        if (active) setError(errorText(cause, '普通消息加载失败'))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id])

  const updateForm = <Key extends keyof NormalMessageForm>(
    key: Key,
    value: NormalMessageForm[Key]
  ) => setForm((current) => ({ ...current, [key]: value }))

  const persist = async () => {
    const token = requireToken()
    if (!canEdit) throw new Error('当前管理员没有普通消息编辑权限')
    const detail = notice
      ? await updateNormalMessage(
        token,
        notice.id,
        normalizeNormalMessageUpdateRequest(form, notice.lifecycleStatus)
      )
      : await createNormalMessage(token, normalizeNormalMessageSaveRequest(form))
    const nextForm = mergeForm(detail, form)
    applyNotice(detail, nextForm, setNotice, setForm)
    if (!notice) navigate(`/content/member-notices/${detail.id}/edit`, { replace: true })
    return detail
  }

  async function saveDraft() {
    const updating = Boolean(notice)
    await runBusy(async () => {
      await persist()
      setMessage(updating ? '消息修改已保存' : '草稿已保存')
    }, updating ? '保存修改失败' : '保存草稿失败')
  }

  const send = async (mode: NormalMessageSendMode) => {
    await runBusy(async () => {
      if (!canSend) throw new Error('当前消息状态或权限不允许发送')
      const scheduledInstant = mode === 'SCHEDULED' ? localDateTimeToInstant(sendAt) : null
      const sendRequest = normalizeNormalMessageSendRequest(form, mode, scheduledInstant)
      const detail = canEdit ? await persist() : notice
      if (!detail) throw new Error('发送前必须先创建普通消息')
      const sent = await sendNormalMessage(requireToken(), detail.id, sendRequest)
      applyNotice(sent, mergeForm(sent, form), setNotice, setForm)
      setSendAt(toLocalDateTime(sent.scheduledAt))
      setMessage(mode === 'IMMEDIATE' ? '消息已立即发送' : '消息已设置定时发送')
    }, mode === 'IMMEDIATE' ? '立即发送失败' : '定时发送失败')
  }

  async function sendNow() {
    await send('IMMEDIATE')
  }

  async function scheduleMessage() {
    await send('SCHEDULED')
  }

  const cancelSchedule = async () => {
    await runBusy(async () => {
      if (!notice || notice.lifecycleStatus !== 'SCHEDULED' || !canSend) {
        throw new Error('当前消息不能取消定时')
      }
      const cancelled = await cancelNormalMessageSchedule(requireToken(), notice.id, form.reason)
      applyNotice(cancelled, mergeForm(cancelled, form), setNotice, setForm)
      setSendAt('')
      setMessage('定时发送已取消')
    }, '取消定时失败')
  }

  const runBusy = async (action: () => Promise<void>, fallback: string) => {
    setSaving(true)
    setError('')
    setMessage('')
    try {
      await action()
    } catch (cause) {
      setError(errorText(cause, fallback))
    } finally {
      setSaving(false)
    }
  }

  const searchUsers = async () => {
    if (!canEdit || audienceFrozen) return
    setSearchingUsers(true)
    setError('')
    try {
      const result = await searchAdminUsers(requireToken(), { q: userQuery, page: 0, size: 20 })
      setUserResults(result.items)
    } catch (cause) {
      setError(errorText(cause, '用户搜索失败'))
    } finally {
      setSearchingUsers(false)
    }
  }

  const toggleUser = (user: AdminUserSearchResult) => {
    if (!canEdit || audienceFrozen) return
    setForm((current) => ({
      ...current,
      selectedUsers: selectedUserIds.has(user.id)
        ? current.selectedUsers.filter(({ id: userId }) => userId !== user.id)
        : [...current.selectedUsers, user]
    }))
  }

  const uploadBodyAsset = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file || !canEdit) return
    await runBusy(async () => {
      const asset = await uploadContentAsset(requireToken(), file, file.name)
      setBodyAsset(asset)
      setBodyAlt(file.name.replace(/\.[^.]+$/u, ''))
      setMessage('正文图片已上传，可从富文本工具栏插入')
    }, '图片上传失败')
  }

  if (id && notice?.id !== id) {
    return error
      ? <div className="state-block error" role="alert">{error}</div>
      : <div className="state-block loading">正在加载普通消息编辑器...</div>
  }
  if (loading) return <div className="state-block loading">正在加载普通消息编辑器...</div>

  return (
    <section className="member-notice-editor-page">
      <header className="member-notice-editor-heading">
        <div>
          <h1>{notice ? '编辑普通消息' : '新建普通消息'}</h1>
          <p>普通消息仅进入消息中心并更新未读数，不会自动弹窗。</p>
        </div>
        <button type="button" className="ghost-button" onClick={() => navigate('/content/member-notices')}>
          返回列表
        </button>
      </header>

      <div className="member-notice-no-popup-notice" role="status">
        普通消息不会自动弹窗。需要弹窗触达时，请使用弹窗活动。
      </div>
      {notice?.lifecycleStatus === 'SENT' ? (
        <div className="member-notice-sent-note" role="status">
          已发送消息的受众已冻结；修改已发送内容不会重置已读状态。
        </div>
      ) : null}
      {deleted ? <div className="member-notice-readonly-note">已删除消息只读，请先在列表恢复。</div> : null}
      {error ? <div className="admin-error" role="alert">{error}</div> : null}
      {message ? <div className="feature-message" role="status">{message}</div> : null}

      <div className="member-notice-editor-grid">
        <section className="member-notice-card">
          <header><h2>消息内容</h2><p>正文仅保存受限编辑器 JSON，由后端生成安全展示内容。</p></header>
          <div className="member-notice-form-grid">
            <label>
              <span>分类</span>
              <input
                value={form.category}
                maxLength={64}
                disabled={!canEdit || Boolean(notice)}
                onChange={(event) => updateForm('category', event.target.value)}
              />
              {notice ? <small>分类创建后不可修改</small> : null}
            </label>
            <label>
              <span>标题</span>
              <input
                value={form.title}
                maxLength={200}
                disabled={!canEdit}
                onChange={(event) => updateForm('title', event.target.value)}
              />
            </label>
          </div>

          <div className="member-notice-editor-field">
            <span>正文</span>
            <Suspense fallback={<div className="state-block loading">正在加载受限富文本编辑器...</div>}>
              <RestrictedRichTextEditor
                value={form.bodyDocument as RestrictedRichTextDocument}
                disabled={!canEdit}
                onChange={(bodyDocument) => updateForm(
                  'bodyDocument',
                  bodyDocument as Readonly<Record<string, unknown>>
                )}
                onPickPlatformImage={() => bodyAsset
                  ? { assetId: bodyAsset.assetId, alt: bodyAlt }
                  : null}
                onPickInternalLink={() => {
                  try {
                    const reference = {
                      routeKey: bodyLinkRoute,
                      params: parseStringMap(bodyLinkParamsText, '内部链接参数')
                    }
                    setError('')
                    return reference
                  } catch (cause) {
                    setError(errorText(cause, '内部链接参数无效'))
                    return null
                  }
                }}
              />
            </Suspense>
          </div>

          <div className="member-notice-asset-field">
            <label>
              <span>正文图片</span>
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                disabled={!canEdit || saving}
                onChange={(event) => { void uploadBodyAsset(event) }}
              />
            </label>
            <label>
              <span>图片替代文本</span>
              <input value={bodyAlt} disabled={!canEdit} onChange={(event) => setBodyAlt(event.target.value)} />
            </label>
            <small>{bodyAsset ? `已上传：${bodyAsset.assetId}` : '上传后从富文本工具栏插入平台图片'}</small>
          </div>

          <fieldset className="member-notice-link-field">
            <legend>正文内部链接</legend>
            <select
              value={bodyLinkRoute}
              disabled={!canEdit}
              onChange={(event) => setBodyLinkRoute(event.target.value as (typeof INTERNAL_ROUTES)[number])}
            >
              {INTERNAL_ROUTES.map((route) => <option key={route} value={route}>{route}</option>)}
            </select>
            <input
              value={bodyLinkParamsText}
              disabled={!canEdit}
              aria-label="内部链接参数 JSON"
              onChange={(event) => setBodyLinkParamsText(event.target.value)}
            />
            <small>先在正文中选中文字，再使用工具栏“内部链接”；参数仅允许字符串键值。</small>
          </fieldset>
        </section>

        <aside className="member-notice-card member-notice-delivery-card">
          <header><h2>受众与发送</h2><p>发送前请确认目标用户和审计原因。</p></header>
          <fieldset className="member-notice-audience-options" disabled={!canEdit || audienceFrozen}>
            <legend>受众</legend>
            <label>
              <input
                type="radio"
                name="audienceType"
                value="ALL"
                checked={form.audienceType === 'ALL'}
                onChange={() => updateForm('audienceType', 'ALL')}
              />
              全部用户（ALL）
            </label>
            <label>
              <input
                type="radio"
                name="audienceType"
                value="SELECTED"
                checked={form.audienceType === 'SELECTED'}
                onChange={() => updateForm('audienceType', 'SELECTED')}
              />
              指定用户（SELECTED）
            </label>
          </fieldset>

          {form.audienceType === 'SELECTED' ? (
            <div className="member-notice-user-picker">
              <div className="member-notice-user-search">
                <input
                  value={userQuery}
                  placeholder="输入完整 UUID、邮箱或手机号"
                  disabled={!canEdit || audienceFrozen}
                  onChange={(event) => setUserQuery(event.target.value)}
                />
                <button
                  type="button"
                  disabled={!canEdit || audienceFrozen || searchingUsers}
                  onClick={() => { void searchUsers() }}
                >
                  {searchingUsers ? '搜索中...' : '搜索'}
                </button>
              </div>
              <p>已选择 {audienceSelection.targetUserIds.length} 位用户</p>
              {form.selectedUsers.filter((user) => !userResults.some(({ id: userId }) => userId === user.id)).map((user) => (
                <label key={user.id} className="member-notice-user-option">
                  <input
                    type="checkbox"
                    checked
                    disabled={!canEdit || audienceFrozen}
                    onChange={() => toggleUser(user)}
                  />
                  <span>{user.email ?? user.phone ?? '已选用户'}</span><small>{user.id}</small>
                </label>
              ))}
              {userResults.map((user) => (
                <label key={user.id} className="member-notice-user-option">
                  <input
                    type="checkbox"
                    checked={selectedUserIds.has(user.id)}
                    disabled={!canEdit || audienceFrozen}
                    onChange={() => toggleUser(user)}
                  />
                  <span>{user.email ?? user.phone ?? user.id}</span><small>{user.id}</small>
                </label>
              ))}
            </div>
          ) : null}

          <label className="member-notice-reason-field">
            <span>操作原因</span>
            <input
              value={form.reason}
              maxLength={500}
              disabled={deleted}
              onChange={(event) => updateForm('reason', event.target.value)}
            />
          </label>

          <label className="member-notice-schedule-field">
            <span>定时发送时间</span>
            <input
              type="datetime-local"
              value={sendAt}
              disabled={!canSend || saving}
              onChange={(event) => setSendAt(event.target.value)}
            />
          </label>

          <div className="member-notice-actions">
            {canEdit ? (
              <button type="button" className="ghost-button" disabled={saving} onClick={() => { void saveDraft() }}>
                {notice ? '保存修改' : '保存草稿'}
              </button>
            ) : null}
            {canSend ? (
              <>
                <button type="button" disabled={saving} onClick={() => { void sendNow() }}>立即发送</button>
                <button type="button" disabled={saving || !sendAt} onClick={() => { void scheduleMessage() }}>
                  定时发送
                </button>
              </>
            ) : null}
            {notice?.lifecycleStatus === 'SCHEDULED' && canSend ? (
              <button type="button" className="danger-button" disabled={saving} onClick={() => { void cancelSchedule() }}>
                取消定时
              </button>
            ) : null}
          </div>
        </aside>
      </div>
    </section>
  )
}

function applyNotice(
  notice: NormalMessageDetail,
  form: NormalMessageForm,
  setNotice: (notice: NormalMessageDetail) => void,
  setForm: (form: NormalMessageForm) => void
) {
  setNotice(notice)
  setForm(form)
}

function mergeForm(notice: NormalMessageDetail, previous: NormalMessageForm) {
  const next = createNormalMessageForm(notice)
  next.reason = previous.reason
  next.selectedUsers = notice.targetUserIds.map((targetId) =>
    previous.selectedUsers.find(({ id }) => id === targetId) ?? { id: targetId })
  return next
}

function requireToken() {
  const token = getValidAdminToken()
  if (!token) throw new Error('登录状态已失效，请重新登录')
  return token
}

function localDateTimeToInstant(value: string) {
  if (!value) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString()
}

function toLocalDateTime(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

function errorText(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback
}

function parseStringMap(value: string, name: string) {
  let parsed: unknown
  try {
    parsed = JSON.parse(value)
  } catch {
    throw new TypeError(`${name}必须是有效 JSON`)
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)
      || Object.values(parsed).some((item) => typeof item !== 'string')) {
    throw new TypeError(`${name}必须是字符串键值对象`)
  }
  return parsed as Readonly<Record<string, string>>
}
