import { type FormEvent, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'

import { getPopupCampaigns, runPopupCampaignAction } from '../services/engagementAdminApi'
import { getAdminAuthorities, getValidAdminToken } from '../services/adminToken'
import {
  campaignActions,
  type PopupCampaignAction,
  type PopupCampaignAudienceType,
  type PopupCampaignLifecycleStatus,
  type PopupCampaignSummary
} from './popupCampaignModel'
import { DataTable, formatDateTime, PageHeader, StateBlock, useAdminData } from './adminPageUtils'

type FilterForm = {
  lifecycleStatus: '' | PopupCampaignLifecycleStatus
  name: string
  audienceType: '' | PopupCampaignAudienceType
  syncToInbox: '' | 'true' | 'false'
  effectiveFrom: string
  effectiveTo: string
}

const EMPTY_FILTERS: FilterForm = {
  lifecycleStatus: '',
  name: '',
  audienceType: '',
  syncToInbox: '',
  effectiveFrom: '',
  effectiveTo: ''
}

type ListAction = Extract<
  PopupCampaignAction,
  'edit' | 'publish' | 'pause' | 'resume' | 'end' | 'reset-delivery'
  | 'delete' | 'restore' | 'stats' | 'users'
>

const LIST_ACTIONS = new Set<ListAction>([
  'edit',
  'publish',
  'pause',
  'resume',
  'end',
  'reset-delivery',
  'delete',
  'restore',
  'stats',
  'users'
])

const ACTION_LABELS: Record<ListAction, string> = {
  'edit': '编辑',
  'publish': '发布审核',
  'pause': '暂停',
  'resume': '恢复投放',
  'end': '结束',
  'reset-delivery': '重置投放次数',
  'delete': '逻辑删除',
  'restore': '恢复活动',
  'stats': '汇总统计',
  'users': '用户明细'
}

type CampaignCommand = Parameters<typeof runPopupCampaignAction>[2]
type LifecycleCommand = Extract<ListAction, CampaignCommand>

export function PopupCampaignPage() {
  const authorities = useMemo(() => new Set(getAdminAuthorities()), [])
  const [filterForm, setFilterForm] = useState<FilterForm>(EMPTY_FILTERS)
  const [filters, setFilters] = useState<FilterForm>(EMPTY_FILTERS)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [filterError, setFilterError] = useState('')
  const [actionError, setActionError] = useState('')
  const [message, setMessage] = useState('')
  const [runningAction, setRunningAction] = useState('')
  const { data, loading, error, reload } = useAdminData(
    (token) => getPopupCampaigns(token, toCampaignQuery(filters, page, size)),
    [filters, page, size]
  )

  const applyFilters = (event: FormEvent) => {
    event.preventDefault()
    if (!validEffectiveWindow(filterForm)) {
      setFilterError('有效期开始时间必须早于结束时间')
      return
    }
    setFilterError('')
    setPage(0)
    setFilters({ ...filterForm, name: filterForm.name.trim() })
  }

  const resetFilters = () => {
    setFilterError('')
    setFilterForm(EMPTY_FILTERS)
    setFilters(EMPTY_FILTERS)
    setPage(0)
  }

  const runAction = async (campaign: PopupCampaignSummary, action: LifecycleCommand) => {
    const reason = window.prompt(`请输入“${ACTION_LABELS[action]}”原因`)?.trim()
    if (!reason) {
      setActionError('操作原因不能为空')
      return
    }
    const token = getValidAdminToken()
    if (!token) {
      setActionError('登录状态已失效，请重新登录')
      return
    }
    const actionKey = `${campaign.id}:${action}`
    setRunningAction(actionKey)
    setActionError('')
    setMessage('')
    try {
      await runPopupCampaignAction(token, campaign.id, action, reason)
      setMessage(`${campaign.name}：${ACTION_LABELS[action]}成功`)
      await reload()
    } catch (caught) {
      setActionError(caught instanceof Error ? caught.message : '活动操作失败')
    } finally {
      setRunningAction('')
    }
  }

  return (
    <section className="feature-page">
      <PageHeader title="弹窗活动" description="创建、筛选并管理定向弹窗活动的完整生命周期。" />

      <div className="feature-toolbar">
        <div className="feature-toolbar-left">
          {authorities.has('content:campaign:edit') ? (
            <Link className="primary-button" to="/content/popup-campaigns/new">新建活动</Link>
          ) : null}
          <Link className="ghost-button" to="/content/popup-campaigns/policy">全局弹窗策略</Link>
        </div>
      </div>

      <form className="feature-filters" onSubmit={applyFilters}>
        <label className="feature-field">
          <span>状态</span>
          <select
            value={filterForm.lifecycleStatus}
            onChange={(event) => updateFilter('lifecycleStatus', event.target.value as FilterForm['lifecycleStatus'])}
          >
            <option value="">全部</option>
            {(['DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED', 'DELETED'] as const).map((status) => (
              <option key={status} value={status}>{status}</option>
            ))}
          </select>
        </label>
        <label className="feature-field">
          <span>名称</span>
          <input value={filterForm.name} onChange={(event) => updateFilter('name', event.target.value)} />
        </label>
        <label className="feature-field">
          <span>受众</span>
          <select
            value={filterForm.audienceType}
            onChange={(event) => updateFilter('audienceType', event.target.value as FilterForm['audienceType'])}
          >
            <option value="">全部</option>
            <option value="ALL">全部用户</option>
            <option value="SELECTED">指定用户</option>
          </select>
        </label>
        <label className="feature-field">
          <span>同步消息</span>
          <select
            value={filterForm.syncToInbox}
            onChange={(event) => updateFilter('syncToInbox', event.target.value as FilterForm['syncToInbox'])}
          >
            <option value="">全部</option>
            <option value="true">是</option>
            <option value="false">否</option>
          </select>
        </label>
        <label className="feature-field">
          <span>有效期起</span>
          <input
            type="datetime-local"
            value={filterForm.effectiveFrom}
            onChange={(event) => updateFilter('effectiveFrom', event.target.value)}
          />
        </label>
        <label className="feature-field">
          <span>有效期止</span>
          <input
            type="datetime-local"
            value={filterForm.effectiveTo}
            onChange={(event) => updateFilter('effectiveTo', event.target.value)}
          />
        </label>
        <div className="feature-filter-actions">
          <button className="ghost-button" type="button" onClick={resetFilters}>重置</button>
          <button className="primary-button" type="submit">查询</button>
        </div>
      </form>

      {filterError ? <StateBlock tone="error">{filterError}</StateBlock> : null}
      {message ? <div className="feature-message">{message}</div> : null}
      {actionError ? <StateBlock tone="error">{actionError}</StateBlock> : null}
      {loading ? <StateBlock tone="loading">正在加载弹窗活动...</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error ? (
        <DataTable
          rows={data?.items ?? []}
          emptyText="没有符合条件的弹窗活动"
          columns={[
            { title: '名称', render: (row) => row.name },
            { title: '状态', render: (row) => row.lifecycleStatus },
            { title: '受众', render: (row) => row.audienceType === 'ALL' ? '全部用户' : `指定用户 (${row.targetCount})` },
            { title: '同步消息', render: (row) => row.syncToInbox ? '是' : '否' },
            { title: '优先级', render: (row) => String(row.priority) },
            { title: '开始时间', render: (row) => formatDateTime(row.startAt) },
            { title: '结束时间', render: (row) => formatDateTime(row.endAt) },
            { title: '更新时间', render: (row) => formatDateTime(row.updatedAt) },
            {
              title: '操作',
              render: (row) => (
                <div className="admin-inline-actions">
                  {campaignActions(row, authorities).filter(isListAction).map((action) => renderAction(row, action))}
                </div>
              )
            }
          ]}
        />
      ) : null}

      {!loading && !error && data ? (
        <div className="feature-pagination">
          <span>共 {data.total} 条，第 {data.page + 1} / {Math.max(data.totalPages, 1)} 页</span>
          <select value={size} onChange={(event) => { setSize(Number(event.target.value)); setPage(0) }}>
            {[10, 20, 50].map((value) => <option key={value} value={value}>{value} 条/页</option>)}
          </select>
          <button type="button" disabled={data.page <= 0} onClick={() => setPage((value) => Math.max(0, value - 1))}>上一页</button>
          <button
            type="button"
            disabled={data.page + 1 >= data.totalPages}
            onClick={() => setPage((value) => value + 1)}
          >下一页</button>
        </div>
      ) : null}
    </section>
  )

  function updateFilter<K extends keyof FilterForm>(key: K, value: FilterForm[K]) {
    setFilterForm((current) => ({ ...current, [key]: value }))
  }

  function renderAction(campaign: PopupCampaignSummary, action: ListAction) {
    if (action === 'edit' || action === 'publish') {
      return <Link key={action} className="ghost-button" to={`/content/popup-campaigns/${campaign.id}/edit`}>{ACTION_LABELS[action]}</Link>
    }
    if (action === 'stats') {
      return <Link key={action} className="ghost-button" to={`/content/popup-campaigns/${campaign.id}/stats`}>{ACTION_LABELS[action]}</Link>
    }
    if (action === 'users') {
      return <Link key={action} className="ghost-button" to={`/content/popup-campaigns/${campaign.id}/users`}>{ACTION_LABELS[action]}</Link>
    }
    return (
      <button
        key={action}
        type="button"
        disabled={runningAction === `${campaign.id}:${action}`}
        onClick={() => void runAction(campaign, action)}
      >
        {runningAction === `${campaign.id}:${action}` ? '处理中...' : ACTION_LABELS[action]}
      </button>
    )
  }
}

function isListAction(action: PopupCampaignAction): action is ListAction {
  return LIST_ACTIONS.has(action as ListAction)
}

function toCampaignQuery(filters: FilterForm, page: number, size: number) {
  return {
    page,
    size,
    lifecycleStatus: filters.lifecycleStatus || undefined,
    name: filters.name || undefined,
    audienceType: filters.audienceType || undefined,
    syncToInbox: filters.syncToInbox === '' ? undefined : filters.syncToInbox === 'true',
    effectiveFrom: toInstant(filters.effectiveFrom),
    effectiveTo: toInstant(filters.effectiveTo)
  }
}

function validEffectiveWindow(filters: FilterForm) {
  if (!filters.effectiveFrom || !filters.effectiveTo) return true
  return new Date(filters.effectiveFrom).getTime() < new Date(filters.effectiveTo).getTime()
}

function toInstant(value: string) {
  return value ? new Date(value).toISOString() : undefined
}
