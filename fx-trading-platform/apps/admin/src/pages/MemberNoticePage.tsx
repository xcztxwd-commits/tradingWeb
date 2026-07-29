import { type FormEvent, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import {
  cancelNormalMessageSchedule,
  deleteNormalMessage,
  getNormalMessages,
  restoreNormalMessage
} from '../services/engagementAdminApi'
import { getAdminAuthorities, getValidAdminToken } from '../services/adminToken'
import { DataTable, formatDateTime, PageHeader, StateBlock, useAdminData } from './adminPageUtils'
import {
  normalMessageActions,
  type NormalMessageAction,
  type NormalMessageLifecycleStatus,
  type NormalMessageSummary
} from './normalMessageModel'
import './MemberNotice.css'

type FilterForm = {
  lifecycleStatus: '' | NormalMessageLifecycleStatus
  title: string
}

type ImmediateListAction = Extract<NormalMessageAction, 'cancel-schedule' | 'delete' | 'restore'>

const EMPTY_FILTERS: FilterForm = { lifecycleStatus: '', title: '' }

const ACTION_LABELS: Record<NormalMessageAction, string> = {
  edit: '编辑',
  'send-now': '立即发送',
  schedule: '定时发送',
  'cancel-schedule': '取消定时',
  delete: '逻辑删除',
  restore: '恢复'
}

export function MemberNoticePage() {
  const authorities = useMemo(() => new Set(getAdminAuthorities()), [])
  const [filterForm, setFilterForm] = useState<FilterForm>(EMPTY_FILTERS)
  const [filters, setFilters] = useState<FilterForm>(EMPTY_FILTERS)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [runningAction, setRunningAction] = useState('')
  const actionInFlight = useRef(false)
  const [actionError, setActionError] = useState('')
  const [message, setMessage] = useState('')
  const { data, loading, error, reload } = useAdminData(
    (token) => getNormalMessages(token, {
      page,
      size,
      lifecycleStatus: filters.lifecycleStatus || undefined,
      title: filters.title || undefined
    }),
    [filters, page, size]
  )

  const applyFilters = (event: FormEvent) => {
    event.preventDefault()
    setPage(0)
    setFilters({ ...filterForm, title: filterForm.title.trim() })
  }

  const resetFilters = () => {
    setFilterForm(EMPTY_FILTERS)
    setFilters(EMPTY_FILTERS)
    setPage(0)
  }

  const runAction = async (notice: NormalMessageSummary, action: ImmediateListAction) => {
    if (actionInFlight.current) return
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

    const actionKey = `${notice.id}:${action}`
    actionInFlight.current = true
    setRunningAction(actionKey)
    setActionError('')
    setMessage('')
    try {
      if (action === 'cancel-schedule') {
        await cancelNormalMessageSchedule(token, notice.id, reason)
      } else if (action === 'delete') {
        await deleteNormalMessage(token, notice.id, reason)
      } else {
        await restoreNormalMessage(token, notice.id, reason)
      }
      setMessage(`${notice.title}：${ACTION_LABELS[action]}成功`)
      await reload()
    } catch (caught) {
      setActionError(caught instanceof Error ? caught.message : '消息操作失败')
    } finally {
      actionInFlight.current = false
      setRunningAction('')
    }
  }

  return (
    <section className="feature-page member-notice-page">
      <PageHeader title="普通消息" description="创建并管理消息中心里的普通消息。" />

      <div className="member-notice-no-popup-notice" role="status">
        普通消息不会自动弹窗，仅出现在用户消息中心并更新未读数。
      </div>

      <div className="feature-toolbar">
        <div className="feature-toolbar-left">
          {authorities.has('content:message:edit') ? (
            <Link className="primary-button" to="/content/member-notices/new">新建消息</Link>
          ) : null}
        </div>
      </div>

      <form className="feature-filters" onSubmit={applyFilters}>
        <label className="feature-field">
          <span>状态</span>
          <select
            value={filterForm.lifecycleStatus}
            onChange={(event) => setFilterForm((current) => ({
              ...current,
              lifecycleStatus: event.target.value as FilterForm['lifecycleStatus']
            }))}
          >
            <option value="">全部</option>
            {(['DRAFT', 'SCHEDULED', 'SENT', 'DELETED'] as const).map((status) => (
              <option key={status} value={status}>{status}</option>
            ))}
          </select>
        </label>
        <label className="feature-field">
          <span>标题</span>
          <input
            value={filterForm.title}
            onChange={(event) => setFilterForm((current) => ({ ...current, title: event.target.value }))}
          />
        </label>
        <div className="feature-filter-actions">
          <button className="ghost-button" type="button" onClick={resetFilters}>重置</button>
          <button className="primary-button" type="submit">查询</button>
        </div>
      </form>

      {message ? <div className="feature-message">{message}</div> : null}
      {actionError ? <StateBlock tone="error">{actionError}</StateBlock> : null}
      {loading ? <StateBlock tone="loading">正在加载普通消息...</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error ? (
        <DataTable
          rows={data?.items ?? []}
          emptyText="没有符合条件的普通消息"
          columns={[
            { title: '标题', render: (row) => row.title },
            {
              title: '状态',
              render: (row) => (
                <span className={`member-notice-status member-notice-status--${row.lifecycleStatus.toLowerCase()}`}>
                  {row.lifecycleStatus}
                </span>
              )
            },
            {
              title: '受众',
              render: (row) => row.audienceType === 'ALL'
                ? '全部用户 (ALL)'
                : `指定用户 (SELECTED · ${row.targetCount})`
            },
            { title: '分类', render: (row) => row.category },
            { title: '定时发送', render: (row) => formatDateTime(row.scheduledAt) },
            { title: '已发送', render: (row) => formatDateTime(row.sentAt) },
            { title: '更新时间', render: (row) => formatDateTime(row.updatedAt) },
            {
              title: '操作',
              render: (row) => (
                <div className="admin-inline-actions">
                  {normalMessageActions(row.lifecycleStatus, authorities).map((action) => renderAction(row, action))}
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

  function renderAction(notice: NormalMessageSummary, action: NormalMessageAction) {
    const editorPath = `/content/member-notices/${notice.id}/edit`
    if (action === 'edit') {
      return (
        <Link
          key={action}
          className="ghost-button"
          to={editorPath}
          aria-disabled={Boolean(runningAction)}
          onClick={(event) => { if (runningAction) event.preventDefault() }}
        >{ACTION_LABELS[action]}</Link>
      )
    }
    if (action === 'send-now' || action === 'schedule') {
      return (
        <Link
          key={action}
          className="ghost-button"
          to={`${editorPath}?mode=${action}`}
          aria-disabled={Boolean(runningAction)}
          onClick={(event) => { if (runningAction) event.preventDefault() }}
        >
          {ACTION_LABELS[action]}
        </Link>
      )
    }
    return (
      <button
        key={action}
        type="button"
        disabled={Boolean(runningAction)}
        onClick={() => void runAction(notice, action)}
      >
        {runningAction === `${notice.id}:${action}` ? '处理中...' : ACTION_LABELS[action]}
      </button>
    )
  }
}
