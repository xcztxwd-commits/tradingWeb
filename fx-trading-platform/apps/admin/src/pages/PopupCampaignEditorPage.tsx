import { lazy, Suspense, useEffect, useMemo, useState, type ChangeEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import type { RestrictedRichTextDocument } from '../components/RestrictedRichTextEditor'
import {
  createPopupCampaign,
  getPopupCampaign,
  runPopupCampaignAction,
  searchAdminUsers,
  updatePopupCampaign,
  uploadContentAsset,
  type PopupCampaignPreviewResponse
} from '../services/engagementAdminApi'
import { getAdminAuthorities, getValidAdminToken } from '../services/adminToken'
import {
  campaignActions,
  createCampaignPreview,
  createPopupCampaignForm,
  isCampaignFieldFrozen,
  normalizeCampaignSaveRequest,
  validateCampaignPublication,
  type AdminUserSearchResult,
  type CampaignPreviewModel,
  type ContentAssetUploadResult,
  type PopupCampaignDetail,
  type PopupCampaignForm,
  type PopupCampaignPageKey,
  type PopupCampaignPublicationMode
} from './popupCampaignModel'
import './PopupCampaign.css'

const RestrictedRichTextEditor = lazy(() => import('../components/RestrictedRichTextEditor'))

const STEPS = [
  { id: 'CONTENT', label: '基本信息与内容' },
  { id: 'AUDIENCE', label: '受众与范围' },
  { id: 'DELIVERY', label: '投放规则' },
  { id: 'REVIEW', label: '预览与发布' }
] as const

const PAGE_OPTIONS: ReadonlyArray<{ value: PopupCampaignPageKey; label: string }> = [
  { value: 'HOME', label: '首页' },
  { value: 'DASHBOARD', label: '仪表盘' },
  { value: 'MARKETS', label: '行情' },
  { value: 'TRADE_SPOT', label: '现货交易' },
  { value: 'TRADE_PERPETUAL', label: '永续交易' },
  { value: 'ORDERS', label: '订单' },
  { value: 'POSITIONS', label: '持仓' },
  { value: 'WALLET', label: '钱包' },
  { value: 'ACCOUNT_OVERVIEW', label: '账户总览' },
  { value: 'ACCOUNT_ASSETS', label: '账户资产' },
  { value: 'FUNDING_RECORDS', label: '资金记录' },
  { value: 'TRADE_RECORDS', label: '交易记录' },
  { value: 'KYC', label: '身份认证' },
  { value: 'ACCOUNT_SETTINGS', label: '账户设置' },
  { value: 'SECURITY', label: '安全设置' },
  { value: 'SETTINGS', label: '偏好设置' },
  { value: 'MESSAGES', label: '消息中心' }
]

const INTERNAL_ROUTES = [
  'HOME', 'DASHBOARD', 'MARKETS', 'ORDERS', 'POSITIONS', 'WALLET',
  'ACCOUNT_OVERVIEW', 'ACCOUNT_ASSETS', 'FUNDING_RECORDS', 'TRADE_RECORDS',
  'KYC', 'ACCOUNT_SETTINGS', 'SECURITY', 'SETTINGS', 'TRADE_SPOT',
  'TRADE_PERPETUAL', 'MESSAGE_CENTER'
] as const

export function PopupCampaignEditorPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [form, setForm] = useState<PopupCampaignForm>(() => createPopupCampaignForm())
  const [campaign, setCampaign] = useState<PopupCampaignDetail>()
  const [currentStep, setCurrentStep] = useState(0)
  const [loading, setLoading] = useState(Boolean(id))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [userQuery, setUserQuery] = useState('')
  const [userResults, setUserResults] = useState<AdminUserSearchResult[]>([])
  const [searchingUsers, setSearchingUsers] = useState(false)
  const [bodyAsset, setBodyAsset] = useState<ContentAssetUploadResult>()
  const [bodyAlt, setBodyAlt] = useState('')
  const [coverAsset, setCoverAsset] = useState<ContentAssetUploadResult>()
  const [ctaParamsText, setCtaParamsText] = useState('{}')
  const [bodyLinkRoute, setBodyLinkRoute] = useState<(typeof INTERNAL_ROUTES)[number]>('MESSAGE_CENTER')
  const [bodyLinkParamsText, setBodyLinkParamsText] = useState('{}')
  const [previewSurface, setPreviewSurface] = useState<'PC' | 'MOBILE'>('PC')
  const [previewSource, setPreviewSource] = useState<PopupCampaignDetail | PopupCampaignPreviewResponse>()

  const authorities = useMemo(() => new Set(getAdminAuthorities()), [])
  const canEdit = authorities.has('content:campaign:edit')
  const hasPublishAuthority = authorities.has('content:campaign:publish')
  const canPublishCampaign = hasPublishAuthority
    && (!campaign ? canEdit : campaignActions(campaign, authorities).includes('publish'))
  const audienceFrozen = campaign ? isCampaignFieldFrozen(campaign, 'audienceType') : false
  const selectedUserIds = useMemo(
    () => new Set(form.selectedUsers.map((user) => user.id)),
    [form.selectedUsers]
  )
  const audienceSelection = useMemo(
    () => ({ targetUserIds: Array.from(selectedUserIds) }),
    [selectedUserIds]
  )
  const preview = useMemo<CampaignPreviewModel | null>(() => {
    if (!previewSource) return null
    try {
      return createCampaignPreview(previewSource, previewSurface)
    } catch {
      return null
    }
  }, [previewSource, previewSurface])

  useEffect(() => {
    if (!id) {
      setLoading(false)
      return
    }
    const token = getValidAdminToken()
    if (!token) {
      setError('登录状态已失效，请重新登录')
      setLoading(false)
      return
    }
    let active = true
    setLoading(true)
    void getPopupCampaign(token, id)
      .then((detail) => {
        if (!active) return
        applyCampaign(detail, createPopupCampaignForm(detail), setForm, setCampaign)
        setPreviewSource(detail)
        setCtaParamsText(detail.ctaParams ?? '{}')
      })
      .catch((cause) => {
        if (active) setError(errorText(cause, '活动加载失败'))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id])

  const updateForm = <Key extends keyof PopupCampaignForm>(key: Key, value: PopupCampaignForm[Key]) => {
    setForm((current) => ({ ...current, [key]: value }))
  }

  const buildCandidate = () => {
    if (!form.cta) return form
    return {
      ...form,
      cta: { ...form.cta, params: parseStringMap(ctaParamsText, 'CTA 参数') }
    }
  }

  const persistCampaign = async (candidate = buildCandidate()) => {
    const token = getValidAdminToken()
    if (!token) throw new Error('登录状态已失效，请重新登录')
    if (!canEdit) throw new Error('当前管理员没有活动编辑权限')
    const request = normalizeCampaignSaveRequest(candidate)
    const detail = campaign
      ? await updatePopupCampaign(token, campaign.id, request)
      : await createPopupCampaign(token, request)
    const nextForm = createPopupCampaignForm(detail)
    nextForm.reason = candidate.reason
    nextForm.selectedUsers = detail.targetUserIds.map((targetId) =>
      candidate.selectedUsers.find((user) => user.id === targetId) ?? { id: targetId })
    applyCampaign(detail, nextForm, setForm, setCampaign)
    setCtaParamsText(detail.ctaParams ?? '{}')
    setPreviewSource(detail)
    if (!campaign) {
      navigate(`/content/popup-campaigns/${detail.id}/edit`, { replace: true })
    }
    return detail
  }

  const saveDraft = async () => {
    setSaving(true)
    setError('')
    setMessage('')
    try {
      await persistCampaign()
      setMessage('草稿已保存，预览已按后端清洗后的内容更新。')
    } catch (cause) {
      setError(errorText(cause, '保存失败'))
    } finally {
      setSaving(false)
    }
  }

  const publishCampaign = async (mode: PopupCampaignPublicationMode) => {
    if (!canPublishCampaign) return
    setSaving(true)
    setError('')
    setMessage('')
    try {
      const now = new Date()
      const candidate = buildCandidate()
      const publishForm = mode === 'IMMEDIATE' && !audienceFrozen && canEdit
        ? { ...candidate, startAt: new Date(now.getTime() - 1_000).toISOString() }
        : candidate
      const errors = validateCampaignPublication(publishForm, mode, now)
      if (errors.length) throw new Error(publicationError(errors))
      setForm(publishForm)
      const detail = canEdit
        ? await persistCampaign(publishForm)
        : campaign
      if (!detail) throw new Error('发布前必须先保存活动')
      const token = getValidAdminToken()
      if (!token) throw new Error('登录状态已失效，请重新登录')
      const published = await runPopupCampaignAction(token, detail.id, 'publish', publishForm.reason)
      const nextForm = createPopupCampaignForm(published)
      nextForm.reason = publishForm.reason
      nextForm.selectedUsers = publishForm.selectedUsers
      applyCampaign(published, nextForm, setForm, setCampaign)
      setPreviewSource(published)
      setMessage(mode === 'IMMEDIATE' ? '活动已立即发布。' : '活动已定时发布。')
    } catch (cause) {
      setError(errorText(cause, '发布失败'))
    } finally {
      setSaving(false)
    }
  }

  const testPopup = async () => {
    setSaving(true)
    setError('')
    setMessage('')
    try {
      const candidate = buildCandidate()
      const detail = await persistCampaign(candidate)
      const token = getValidAdminToken()
      if (!token) throw new Error('登录状态已失效，请重新登录')
      const tested = await runPopupCampaignAction(token, detail.id, 'test-popup', candidate.reason)
      if (!tested.preview) throw new Error('测试弹窗未返回 PREVIEW 标记')
      setPreviewSource(tested)
      setMessage('测试弹窗仅生成 PREVIEW，并未创建正式投放数据。')
    } catch (cause) {
      setError(errorText(cause, '测试弹窗失败'))
    } finally {
      setSaving(false)
    }
  }

  const searchUsers = async () => {
    const token = getValidAdminToken()
    if (!token || !canEdit) return
    setSearchingUsers(true)
    setError('')
    try {
      const page = await searchAdminUsers(token, { q: userQuery, page: 0, size: 20 })
      setUserResults(page.items)
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
        ? current.selectedUsers.filter((item) => item.id !== user.id)
        : [...current.selectedUsers, user]
    }))
  }

  const uploadAsset = async (event: ChangeEvent<HTMLInputElement>, placement: 'body' | 'cover') => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    const token = getValidAdminToken()
    if (!token || !canEdit) return
    setSaving(true)
    setError('')
    try {
      const asset = await uploadContentAsset(token, file, file.name)
      if (placement === 'body') {
        setBodyAsset(asset)
        setBodyAlt(file.name.replace(/\.[^.]+$/u, ''))
      } else {
        setCoverAsset(asset)
        updateForm('coverAssetId', asset.assetId)
      }
    } catch (cause) {
      setError(errorText(cause, '图片上传失败'))
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div className="state-block loading">正在加载活动编辑器</div>

  return (
    <div className="popup-campaign-editor-page">
      <header className="popup-campaign-heading">
        <div>
          <h1>{campaign ? '编辑弹窗活动' : '新建弹窗活动'}</h1>
          <p>正文、受众与投放规则均由后端校验；已发布活动的冻结字段不可修改。</p>
        </div>
        <button type="button" className="ghost-button" onClick={() => navigate('/content/popup-campaigns')}>
          返回列表
        </button>
      </header>

      <nav className="popup-campaign-steps" aria-label="活动编辑步骤">
        {STEPS.map((step, index) => (
          <button
            key={step.id}
            type="button"
            className={currentStep === index ? 'is-active' : currentStep > index ? 'is-complete' : ''}
            aria-current={currentStep === index ? 'step' : undefined}
            onClick={() => setCurrentStep(index)}
          >
            <span>{index + 1}</span>
            {step.label}
          </button>
        ))}
      </nav>

      {error ? <div className="admin-error" role="alert">{error}</div> : null}
      {message ? <div className="feature-message" role="status">{message}</div> : null}

      <section className="popup-campaign-workspace">
        {currentStep === 0 ? (
          <ContentStep
            form={form}
            updateForm={updateForm}
            campaign={campaign}
            canEdit={canEdit}
            bodyAsset={bodyAsset}
            bodyAlt={bodyAlt}
            setBodyAlt={setBodyAlt}
            coverAsset={coverAsset}
            ctaParamsText={ctaParamsText}
            setCtaParamsText={setCtaParamsText}
            bodyLinkRoute={bodyLinkRoute}
            setBodyLinkRoute={setBodyLinkRoute}
            bodyLinkParamsText={bodyLinkParamsText}
            setBodyLinkParamsText={setBodyLinkParamsText}
            uploadAsset={uploadAsset}
            setError={setError}
          />
        ) : null}
        {currentStep === 1 ? (
          <AudienceStep
            form={form}
            updateForm={updateForm}
            audienceFrozen={audienceFrozen}
            canEdit={canEdit}
            userQuery={userQuery}
            setUserQuery={setUserQuery}
            userResults={userResults}
            selectedUserIds={selectedUserIds}
            selectedCount={audienceSelection.targetUserIds.length}
            searchingUsers={searchingUsers}
            searchUsers={searchUsers}
            toggleUser={toggleUser}
          />
        ) : null}
        {currentStep === 2 ? (
          <DeliveryStep form={form} updateForm={updateForm} campaign={campaign} canEdit={canEdit} />
        ) : null}
        {currentStep === 3 ? (
          <ReviewStep
            form={form}
            updateForm={updateForm}
            campaign={campaign}
            preview={preview}
            previewSurface={previewSurface}
            setPreviewSurface={setPreviewSurface}
            canEdit={canEdit}
            canPublish={canPublishCampaign}
            saving={saving}
            goBack={() => setCurrentStep(2)}
            saveDraft={saveDraft}
            testPopup={testPopup}
            publishCampaign={publishCampaign}
          />
        ) : null}
      </section>

      {currentStep < 3 ? (
        <footer className="popup-campaign-sticky-actions">
          <button type="button" className="ghost-button" disabled={currentStep === 0}
            onClick={() => setCurrentStep((step) => Math.max(0, step - 1))}>
            上一步
          </button>
          <button type="button" className="primary-button"
            onClick={() => setCurrentStep((step) => Math.min(3, step + 1))}>
            下一步
          </button>
        </footer>
      ) : null}
    </div>
  )
}

type UpdateForm = <Key extends keyof PopupCampaignForm>(key: Key, value: PopupCampaignForm[Key]) => void

function ContentStep({
  form,
  updateForm,
  campaign,
  canEdit,
  bodyAsset,
  bodyAlt,
  setBodyAlt,
  coverAsset,
  ctaParamsText,
  setCtaParamsText,
  bodyLinkRoute,
  setBodyLinkRoute,
  bodyLinkParamsText,
  setBodyLinkParamsText,
  uploadAsset,
  setError
}: {
  form: PopupCampaignForm
  updateForm: UpdateForm
  campaign?: PopupCampaignDetail
  canEdit: boolean
  bodyAsset?: ContentAssetUploadResult
  bodyAlt: string
  setBodyAlt: (value: string) => void
  coverAsset?: ContentAssetUploadResult
  ctaParamsText: string
  setCtaParamsText: (value: string) => void
  bodyLinkRoute: (typeof INTERNAL_ROUTES)[number]
  setBodyLinkRoute: (value: (typeof INTERNAL_ROUTES)[number]) => void
  bodyLinkParamsText: string
  setBodyLinkParamsText: (value: string) => void
  uploadAsset: (event: ChangeEvent<HTMLInputElement>, placement: 'body' | 'cover') => Promise<void>
  setError: (value: string) => void
}) {
  const nameFrozen = campaign ? isCampaignFieldFrozen(campaign, 'name') : false
  const templateFrozen = campaign ? isCampaignFieldFrozen(campaign, 'templateSize') : false
  const disabled = !canEdit
  return (
    <div className="popup-campaign-step-panel">
      <header><h2>基本信息与内容</h2><p>正文只保存受限编辑器 JSON，HTML 仅由后端白名单生成。</p></header>
      <div className="popup-campaign-form-grid">
        <label><span>活动名称</span><input value={form.name} maxLength={200}
          disabled={disabled || nameFrozen} onChange={(event) => updateForm('name', event.target.value)} /></label>
        <label><span>弹窗标题</span><input value={form.title} maxLength={200}
          disabled={disabled} onChange={(event) => updateForm('title', event.target.value)} /></label>
        <label><span>模板尺寸</span><select value={form.templateSize} disabled={disabled || templateFrozen}
          onChange={(event) => updateForm('templateSize', event.target.value as PopupCampaignForm['templateSize'])}>
          <option value="SMALL">小</option><option value="MEDIUM">中</option><option value="LARGE">大</option>
        </select></label>
        <label className="popup-campaign-file-field"><span>封面图片</span>
          <input type="file" accept="image/jpeg,image/png,image/webp" disabled={disabled}
            onChange={(event) => { void uploadAsset(event, 'cover') }} />
          <small>{coverAsset ? `${coverAsset.mimeType} · ${coverAsset.width}×${coverAsset.height}`
            : form.coverAssetId ? `资产 ${form.coverAssetId}` : '可选，最大 5 MiB'}</small>
        </label>
      </div>

      <div className="popup-campaign-editor-field">
        <span>正文</span>
        <Suspense fallback={<div className="state-block loading">正在加载受限富文本编辑器</div>}>
          <RestrictedRichTextEditor
            value={form.bodyDocument as RestrictedRichTextDocument}
            disabled={disabled}
            onChange={(bodyDocument) => updateForm(
              'bodyDocument', bodyDocument as Readonly<Record<string, unknown>>)}
            onPickPlatformImage={() => {
              if (!bodyAsset) {
                setError('请先上传正文图片，再从工具栏插入。')
                return null
              }
              return { assetId: bodyAsset.assetId, alt: bodyAlt }
            }}
            onPickInternalLink={() => {
              try {
                return { routeKey: bodyLinkRoute, params: parseStringMap(bodyLinkParamsText, '内部链接参数') }
              } catch (cause) {
                setError(errorText(cause, '内部链接参数无效'))
                return null
              }
            }}
          />
        </Suspense>
      </div>

      <div className="popup-campaign-controlled-content">
        <fieldset><legend>正文平台图片</legend>
          <input type="file" accept="image/jpeg,image/png,image/webp" disabled={disabled}
            onChange={(event) => { void uploadAsset(event, 'body') }} />
          <input value={bodyAlt} placeholder="图片替代文本" disabled={disabled}
            onChange={(event) => setBodyAlt(event.target.value)} />
          <small>{bodyAsset ? `已就绪：${bodyAsset.assetId}` : '上传后使用编辑器“平台图片”按钮插入'}</small>
        </fieldset>
        <fieldset><legend>正文内部链接</legend>
          <select value={bodyLinkRoute} disabled={disabled}
            onChange={(event) => setBodyLinkRoute(event.target.value as (typeof INTERNAL_ROUTES)[number])}>
            {INTERNAL_ROUTES.map((route) => <option key={route} value={route}>{route}</option>)}
          </select>
          <input value={bodyLinkParamsText} disabled={disabled} aria-label="内部链接参数 JSON"
            onChange={(event) => setBodyLinkParamsText(event.target.value)} />
          <small>在正文中选中文字，再使用编辑器“内部链接”按钮。</small>
        </fieldset>
      </div>

      <fieldset className="popup-campaign-cta"><legend>单个 CTA</legend>
        <label className="popup-campaign-check"><input type="checkbox" checked={Boolean(form.cta)} disabled={disabled}
          onChange={(event) => updateForm('cta', event.target.checked
            ? { label: '', routeKey: 'MESSAGE_CENTER', params: {} } : null)} />启用 CTA</label>
        {form.cta ? <div className="popup-campaign-form-grid">
          <label><span>按钮文案</span><input value={form.cta.label} maxLength={80} disabled={disabled}
            onChange={(event) => updateForm('cta', { ...form.cta!, label: event.target.value })} /></label>
          <label><span>内部路由</span><select value={form.cta.routeKey} disabled={disabled}
            onChange={(event) => updateForm('cta', { ...form.cta!, routeKey: event.target.value })}>
            {INTERNAL_ROUTES.map((route) => <option key={route} value={route}>{route}</option>)}
          </select></label>
          <label className="popup-campaign-span-two"><span>路由参数 JSON</span>
            <input value={ctaParamsText} disabled={disabled} onChange={(event) => setCtaParamsText(event.target.value)} />
          </label>
        </div> : null}
      </fieldset>
    </div>
  )
}

function AudienceStep({
  form, updateForm, audienceFrozen, canEdit, userQuery, setUserQuery, userResults,
  selectedUserIds, selectedCount, searchingUsers, searchUsers, toggleUser
}: {
  form: PopupCampaignForm
  updateForm: UpdateForm
  audienceFrozen: boolean
  canEdit: boolean
  userQuery: string
  setUserQuery: (value: string) => void
  userResults: AdminUserSearchResult[]
  selectedUserIds: ReadonlySet<string>
  selectedCount: number
  searchingUsers: boolean
  searchUsers: () => Promise<void>
  toggleUser: (user: AdminUserSearchResult) => void
}) {
  const disabled = !canEdit
  return (
    <div className="popup-campaign-step-panel">
      <header><h2>受众与范围</h2><p>发布后受众名单与消息中心同步设置冻结，后端仍会拒绝绕过调用。</p></header>
      <fieldset><legend>受众</legend>
        <div className="popup-campaign-choice-row">
          <label><input type="radio" name="audience" checked={form.audienceType === 'ALL'}
            disabled={disabled || audienceFrozen} onChange={() => updateForm('audienceType', 'ALL')} />全部用户</label>
          <label><input type="radio" name="audience" checked={form.audienceType === 'SELECTED'}
            disabled={disabled || audienceFrozen} onChange={() => updateForm('audienceType', 'SELECTED')} />搜索多选用户</label>
        </div>
        {form.audienceType === 'SELECTED' ? <>
          <div className="popup-campaign-user-search">
            <input value={userQuery} placeholder="邮箱、手机号或用户 UUID" disabled={disabled || audienceFrozen}
              onChange={(event) => setUserQuery(event.target.value)} onKeyDown={(event) => {
                if (event.key === 'Enter') { event.preventDefault(); void searchUsers() }
              }} />
            <button type="button" className="ghost-button" disabled={disabled || audienceFrozen || searchingUsers}
              onClick={() => { void searchUsers() }}>{searchingUsers ? '搜索中' : '搜索'}</button>
          </div>
          <p className="popup-campaign-selection-count">已选择 {selectedCount} 位用户</p>
          <div className="popup-campaign-user-columns">
            <div><strong>搜索结果</strong>{userResults.length ? userResults.map((user) => (
              <label key={user.id} className="popup-campaign-user-row">
                <input type="checkbox" checked={selectedUserIds.has(user.id)} disabled={disabled || audienceFrozen}
                  onChange={() => toggleUser(user)} />
                <span>{user.email ?? user.phone ?? user.id}<small>{user.id} · {user.status ?? '-'}</small></span>
              </label>
            )) : <small>输入条件搜索业务用户。</small>}</div>
            <div><strong>已选用户</strong>{form.selectedUsers.length ? form.selectedUsers.map((user) => (
              <label key={user.id} className="popup-campaign-user-row">
                <input type="checkbox" checked disabled={disabled || audienceFrozen} onChange={() => toggleUser(user)} />
                <span>{user.email ?? user.phone ?? user.id}<small>{user.id}</small></span>
              </label>
            )) : <small>尚未选择用户。</small>}</div>
          </div>
        </> : null}
      </fieldset>

      <label className="popup-campaign-switch"><span><strong>同步消息中心</strong><small>活动发布后同步为站内普通消息。</small></span>
        <input type="checkbox" checked={form.syncToInbox} disabled={disabled || audienceFrozen}
          onChange={(event) => updateForm('syncToInbox', event.target.checked)} /></label>

      <fieldset><legend>业务页面</legend>
        <div className="popup-campaign-choice-row">
          <label><input type="radio" name="displayScope" checked={form.displayScope === 'ALL_BUSINESS_PAGES'}
            disabled={disabled} onChange={() => updateForm('displayScope', 'ALL_BUSINESS_PAGES')} />全部业务页面</label>
          <label><input type="radio" name="displayScope" checked={form.displayScope === 'SELECTED_PAGES'}
            disabled={disabled} onChange={() => updateForm('displayScope', 'SELECTED_PAGES')} />指定页面</label>
        </div>
        {form.displayScope === 'SELECTED_PAGES' ? <div className="popup-campaign-page-options">
          {PAGE_OPTIONS.map((page) => <label key={page.value}><input type="checkbox"
            checked={form.pageKeys.includes(page.value)} disabled={disabled}
            onChange={() => updateForm('pageKeys', form.pageKeys.includes(page.value)
              ? form.pageKeys.filter((key) => key !== page.value) : [...form.pageKeys, page.value])} />{page.label}</label>)}
        </div> : null}
      </fieldset>

      <fieldset><legend>终端范围</legend><div className="popup-campaign-choice-row">
        {(['ALL', 'PC', 'MOBILE'] as const).map((device) => <label key={device}>
          <input type="radio" name="device" checked={form.deviceScope === device} disabled={disabled}
            onChange={() => updateForm('deviceScope', device)} />{device === 'ALL' ? '全部' : device}
        </label>)}
      </div></fieldset>
    </div>
  )
}

function DeliveryStep({ form, updateForm, campaign, canEdit }: {
  form: PopupCampaignForm
  updateForm: UpdateForm
  campaign?: PopupCampaignDetail
  canEdit: boolean
}) {
  const startFrozen = campaign ? isCampaignFieldFrozen(campaign, 'startAt') : false
  const timeZoneFrozen = campaign ? isCampaignFieldFrozen(campaign, 'timeZone') : false
  const disabled = !canEdit
  return <div className="popup-campaign-step-panel">
    <header><h2>投放规则</h2><p>时间按选定时区解释；总次数、每日次数与最小间隔最终由后端执行。</p></header>
    <div className="popup-campaign-form-grid popup-campaign-delivery-grid">
      <label><span>开始时间</span><input type="datetime-local" value={toLocalInput(form.startAt)}
        disabled={disabled || startFrozen} onChange={(event) => updateInstant(updateForm, 'startAt', event.target.value)} /></label>
      <label><span>结束时间</span><input type="datetime-local" value={toLocalInput(form.endAt)}
        disabled={disabled} onChange={(event) => updateInstant(updateForm, 'endAt', event.target.value)} /></label>
      <label><span>时区</span><select value={form.timeZone} disabled={disabled || timeZoneFrozen}
        onChange={(event) => updateForm('timeZone', event.target.value)}>
        {['Asia/Shanghai', 'UTC', 'America/New_York', 'Europe/London'].map((zone) =>
          <option key={zone} value={zone}>{zone}</option>)}
      </select></label>
      <label><span>优先级</span><input type="number" min={-100000} max={100000} value={form.priority}
        disabled={disabled} onChange={(event) => updateForm('priority', Number(event.target.value))} /></label>
      <label><span>总展示上限</span><input type="number" min={1} value={form.maxTotalImpressions}
        disabled={disabled} onChange={(event) => updateForm('maxTotalImpressions', Number(event.target.value))} /></label>
      <label><span>每日展示上限</span><input type="number" min={1} value={form.maxDailyImpressions}
        disabled={disabled} onChange={(event) => updateForm('maxDailyImpressions', Number(event.target.value))} /></label>
      <label><span>最小间隔（秒）</span><input type="number" min={0} value={form.minIntervalSeconds}
        disabled={disabled} onChange={(event) => updateForm('minIntervalSeconds', Number(event.target.value))} /></label>
    </div>
  </div>
}

function ReviewStep({
  form, updateForm, campaign, preview, previewSurface, setPreviewSurface,
  canEdit, canPublish, saving, goBack, saveDraft, testPopup, publishCampaign
}: {
  form: PopupCampaignForm
  updateForm: UpdateForm
  campaign?: PopupCampaignDetail
  preview: CampaignPreviewModel | null
  previewSurface: 'PC' | 'MOBILE'
  setPreviewSurface: (surface: 'PC' | 'MOBILE') => void
  canEdit: boolean
  canPublish: boolean
  saving: boolean
  goBack: () => void
  saveDraft: () => Promise<void>
  testPopup: () => Promise<void>
  publishCampaign: (mode: PopupCampaignPublicationMode) => Promise<void>
}) {
  const startsInFuture = Date.parse(form.startAt) > Date.now()
  const canPublishImmediately = canPublish && (canEdit || !startsInFuture)
  const canPublishScheduled = canPublish && (canEdit || startsInFuture)
  return <div className="popup-campaign-review-step">
    <section className="popup-campaign-review-summary">
      <header><h2>活动信息总览</h2><p>发布前核对后端将使用的配置。</p></header>
      <dl>
        <div><dt>活动名称</dt><dd>{form.name || '-'}</dd></div>
        <div><dt>状态</dt><dd>{campaign?.lifecycleStatus ?? 'DRAFT'}</dd></div>
        <div><dt>受众</dt><dd>{form.audienceType === 'ALL' ? '全部用户' : `${form.selectedUsers.length} 位选定用户`}</dd></div>
        <div><dt>同步消息中心</dt><dd>{form.syncToInbox ? '是' : '否'}</dd></div>
        <div><dt>页面 / 终端</dt><dd>{form.displayScope === 'ALL_BUSINESS_PAGES' ? '全部业务页面' : `${form.pageKeys.length} 个页面`} · {form.deviceScope}</dd></div>
        <div><dt>投放时间</dt><dd>{formatWindow(form.startAt, form.endAt)}</dd></div>
        <div><dt>时区 / 优先级</dt><dd>{form.timeZone} · {form.priority}</dd></div>
        <div><dt>频控</dt><dd>总计 {form.maxTotalImpressions} / 每日 {form.maxDailyImpressions} / 间隔 {form.minIntervalSeconds}s</dd></div>
      </dl>
    </section>
    <section className="popup-campaign-preview-workbench">
      <header><h2>预览工作台</h2><div className="popup-campaign-preview-tabs">
        <button type="button" className={previewSurface === 'PC' ? 'is-active' : ''}
          onClick={() => setPreviewSurface('PC')}>PC</button>
        <button type="button" className={previewSurface === 'MOBILE' ? 'is-active' : ''}
          onClick={() => setPreviewSurface('MOBILE')}>Mobile</button>
      </div></header>
      {preview ? <CampaignPreview preview={preview} />
        : <div className="state-block empty">先保存草稿，后端清洗完成后即可预览。</div>}
    </section>
    <footer className="popup-campaign-review-actions">
      <label><span><strong>操作原因</strong><small>保存、测试和发布都会写入审计。</small></span>
        <input value={form.reason} maxLength={500} disabled={!canEdit && !canPublish}
          onChange={(event) => updateForm('reason', event.target.value)} placeholder="必填，最多 500 字" /></label>
      <div>
        <button type="button" className="ghost-button" disabled={saving} onClick={goBack}>上一步</button>
        {canEdit ? <button type="button" className="ghost-button" disabled={saving}
          onClick={() => { void saveDraft() }}>保存草稿</button> : null}
        {canEdit ? <button type="button" className="ghost-button" disabled={saving || !campaign}
          onClick={() => { void testPopup() }}>测试弹窗</button> : null}
        {canPublishScheduled ? <button type="button" className="ghost-button" disabled={saving}
          onClick={() => { void publishCampaign('SCHEDULED') }}>定时发布</button> : null}
        {canPublishImmediately ? <button type="button" className="primary-button" disabled={saving}
          onClick={() => { void publishCampaign('IMMEDIATE') }}>立即发布</button> : null}
      </div>
    </footer>
  </div>
}

function CampaignPreview({ preview }: { preview: CampaignPreviewModel }) {
  return <div className={`popup-campaign-preview popup-campaign-preview--${preview.surface.toLowerCase()} popup-campaign-preview--${preview.sizeMode.toLowerCase()}`}>
    <strong className="popup-campaign-preview-watermark">PREVIEW</strong>
    <article>
      {preview.coverAsset ? <img src={preview.coverAsset.url} alt="平台封面" /> : null}
      <div><h3>{preview.title}</h3>
        <iframe title="后端清洗正文预览" sandbox="" srcDoc={previewFrameDocument(preview.sanitizedHtml)} />
        {preview.cta ? <button type="button" tabIndex={-1}>{preview.cta.label}</button> : null}
      </div>
    </article>
  </div>
}

function applyCampaign(
  detail: PopupCampaignDetail,
  nextForm: PopupCampaignForm,
  setForm: (form: PopupCampaignForm) => void,
  setCampaign: (campaign: PopupCampaignDetail) => void
) {
  setCampaign(detail)
  setForm(nextForm)
}

function parseStringMap(value: string, name: string) {
  let parsed: unknown
  try { parsed = JSON.parse(value) } catch { throw new TypeError(`${name}必须是 JSON 对象`) }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new TypeError(`${name}必须是 JSON 对象`)
  for (const item of Object.values(parsed)) {
    if (typeof item !== 'string') throw new TypeError(`${name}中的值必须是文本`)
  }
  return parsed as Readonly<Record<string, string>>
}

function updateInstant(updateForm: UpdateForm, field: 'startAt' | 'endAt', value: string) {
  if (!value) return updateForm(field, '')
  const date = new Date(value)
  updateForm(field, Number.isNaN(date.getTime()) ? '' : date.toISOString())
}

function toLocalInput(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function previewFrameDocument(sanitizedHtml: string) {
  const body = sanitizedHtml.replace(
    /<img data-asset-id="([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})"/giu,
    '<img src="/api/public/engagement/assets/$1" data-asset-id="$1"'
  )
  return `<!doctype html><meta charset="utf-8"><style>body{margin:0;color:#334155;font:14px/1.65 Inter,system-ui,sans-serif}img{max-width:100%;height:auto}a{color:#1e40af}h1,h2,h3,p{margin:0 0 10px}ul,ol{padding-left:22px}</style><body>${body}</body>`
}

function formatWindow(startAt: string, endAt: string) {
  return `${new Date(startAt).toLocaleString('zh-CN', { hour12: false })} — ${new Date(endAt).toLocaleString('zh-CN', { hour12: false })}`
}

function publicationError(errors: string[]) {
  const translations: Record<string, string> = {
    'name is required': '请填写活动名称',
    'title is required': '请填写弹窗标题',
    'reason is required': '请填写操作原因',
    'timeZone is invalid': '请选择有效时区',
    'endAt must be after startAt': '结束时间必须晚于开始时间',
    'startAt must be in the future for scheduled publication': '定时发布的开始时间必须晚于当前时间',
    'startAt must not be in the future for immediate publication': '立即发布的开始时间不能晚于当前时间',
    'endAt must be in the future': '结束时间必须晚于当前时间',
    'selected users are required': '定向受众至少选择一位用户'
  }
  return errors.map((item) => translations[item] ?? item).join('；')
}

function errorText(cause: unknown, fallback: string) {
  return cause instanceof Error && cause.message ? cause.message : fallback
}
