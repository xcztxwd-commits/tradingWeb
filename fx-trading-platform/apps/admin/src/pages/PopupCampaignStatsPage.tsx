import { type FormEvent, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { getPopupCampaignStats, getPopupCampaignUsers } from '../services/engagementAdminApi'
import { getAdminAuthorities, getValidAdminToken } from '../services/adminToken'
import type { PopupCampaignStats, PopupCampaignUserDetail } from './popupCampaignModel'
import { DataTable, formatDateTime, PageHeader, StateBlock, useAdminData } from './adminPageUtils'

const USER_PAGE_SIZE = 20

const STAT_FIELDS: ReadonlyArray<{ key: keyof PopupCampaignStats; label: string }> = [
  { key: 'targetCount', label: '目标用户' },
  { key: 'usersWithState', label: '已有投放状态用户' },
  { key: 'totalImpressions', label: '累计展示' },
  { key: 'optedOutUsers', label: '不再提醒用户' },
  { key: 'clickedUsers', label: '点击用户' },
  { key: 'issuedDeliveries', label: '已领取' },
  { key: 'shownDeliveries', label: '已展示' },
  { key: 'closedDeliveries', label: '已关闭' },
  { key: 'clickedDeliveries', label: '已点击' },
  { key: 'invalidatedDeliveries', label: '已失效' },
  { key: 'expiredDeliveries', label: '已过期' }
]

export function PopupCampaignStatsPage({ userDetail = false }: { userDetail?: boolean }) {
  const { id: campaignId = '' } = useParams()
  const authorities = useMemo(() => new Set(getAdminAuthorities()), [])
  const canViewStats = authorities.has('content:campaign:stats')
  const canViewUsers = authorities.has('content:campaign:user-detail')
  const showStats = !userDetail || canViewStats
  const { data: stats, loading, error } = useAdminData(
    (token) => {
      if (!campaignId) throw new Error('活动 ID 缺失')
      if (userDetail && !canViewStats) return Promise.resolve(undefined)
      if (!canViewStats) throw new Error('缺少 content:campaign:stats 权限')
      return getPopupCampaignStats(token, campaignId)
    },
    [campaignId, canViewStats, userDetail]
  )
  const [reason, setReason] = useState('')
  const [submittedReason, setSubmittedReason] = useState('')
  const [userPage, setUserPage] = useState<Awaited<ReturnType<typeof getPopupCampaignUsers>>>()
  const [usersLoading, setUsersLoading] = useState(false)
  const [usersError, setUsersError] = useState('')
  const userRequestGeneration = useRef(0)

  useEffect(() => {
    userRequestGeneration.current += 1
    setReason('')
    setSubmittedReason('')
    setUserPage(undefined)
    setUsersLoading(false)
    setUsersError('')
    return () => {
      userRequestGeneration.current += 1
    }
  }, [campaignId, userDetail])

  const submitUserDetail = (event: FormEvent) => {
    event.preventDefault()
    const requiredReason = reason.trim()
    if (!requiredReason) {
      setUsersError('查看用户级投放明细必须填写原因')
      return
    }
    setSubmittedReason(requiredReason)
    void loadUsers(0, requiredReason)
  }

  const loadUsers = async (page: number, accessReason: string) => {
    if (!canViewUsers || !campaignId) return
    const requestGeneration = ++userRequestGeneration.current
    const token = getValidAdminToken()
    if (!token) {
      setUsersLoading(false)
      setUsersError('登录状态已失效，请重新登录')
      return
    }
    setUsersLoading(true)
    setUsersError('')
    try {
      const next = await getPopupCampaignUsers(token, campaignId, {
        page,
        size: USER_PAGE_SIZE,
        reason: accessReason
      })
      if (requestGeneration !== userRequestGeneration.current) return
      setUserPage(next)
    } catch (caught) {
      if (requestGeneration !== userRequestGeneration.current) return
      setUserPage(undefined)
      setUsersError(caught instanceof Error ? caught.message : '用户级投放明细加载失败')
    } finally {
      if (requestGeneration === userRequestGeneration.current) setUsersLoading(false)
    }
  }

  return (
    <section className="feature-page">
      <PageHeader
        title={userDetail ? '活动用户级投放明细' : '活动汇总统计'}
        description={userDetail
          ? '高权限查看入口；每次查询都要求填写审计原因。累计展示与用户终态长期保留，领取、展示、关闭、点击、失效和过期仅统计原始明细保留期。'
          : '累计展示与用户终态长期保留；领取、展示、关闭、点击、失效和过期仅统计原始明细保留期。'}
      />
      <div className="feature-toolbar">
        <div className="feature-toolbar-left">
          <Link className="ghost-button" to="/content/popup-campaigns">返回活动列表</Link>
          {canViewStats && userDetail ? (
            <Link className="ghost-button" to={`/content/popup-campaigns/${campaignId}/stats`}>汇总统计</Link>
          ) : null}
          {canViewUsers && !userDetail ? (
            <Link className="ghost-button" to={`/content/popup-campaigns/${campaignId}/users`}>用户级明细</Link>
          ) : null}
        </div>
      </div>

      {showStats && loading ? <StateBlock tone="loading">正在加载汇总统计...</StateBlock> : null}
      {showStats && error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {showStats && !loading && !error && !stats ? <StateBlock>暂无活动统计</StateBlock> : null}
      {showStats && !loading && !error && stats ? (
        <section className="stat-grid">
          {STAT_FIELDS.map(({ key, label }) => (
            <div className="stat" key={key}>
              <span>{label}</span>
              <strong>{stats[key]}</strong>
            </div>
          ))}
        </section>
      ) : null}

      {userDetail && !canViewUsers ? (
        <StateBlock tone="error">缺少 content:campaign:user-detail 权限，不能查看用户级投放明细。</StateBlock>
      ) : null}

      {userDetail && canViewUsers ? (
        <section className="operation-section">
          <form className="admin-config-form" onSubmit={submitUserDetail}>
            <div className="admin-form-grid">
              <label className="admin-field-wide">
                查看原因
                <input
                  value={reason}
                  maxLength={500}
                  required
                  disabled={usersLoading}
                  onChange={(event) => setReason(event.target.value)}
                />
                <span className="admin-field-hint">原因会随请求发送并写入审计日志。</span>
              </label>
            </div>
            <div className="admin-action-row">
              <button type="submit" disabled={usersLoading}>{usersLoading ? '查询中...' : '查询用户明细'}</button>
            </div>
          </form>

          {usersLoading ? <StateBlock tone="loading">正在加载用户级投放明细...</StateBlock> : null}
          {usersError ? <StateBlock tone="error">{usersError}</StateBlock> : null}
          {!usersLoading && !usersError && !userPage ? <StateBlock>填写原因后查询用户级投放明细</StateBlock> : null}
          {!usersLoading && !usersError && userPage ? (
            <>
              <DataTable
                rows={userPage.items}
                emptyText="该活动暂无用户级投放明细"
                columns={userColumns()}
              />
              <div className="feature-pagination">
                <span>共 {userPage.total} 条，第 {userPage.page + 1} / {Math.max(userPage.totalPages, 1)} 页</span>
                <button
                  type="button"
                  disabled={userPage.page <= 0 || usersLoading}
                  onClick={() => void loadUsers(Math.max(0, userPage.page - 1), submittedReason)}
                >上一页</button>
                <button
                  type="button"
                  disabled={userPage.page + 1 >= userPage.totalPages || usersLoading}
                  onClick={() => void loadUsers(userPage.page + 1, submittedReason)}
                >下一页</button>
              </div>
            </>
          ) : null}
        </section>
      ) : null}
    </section>
  )
}

function userColumns() {
  return [
    { title: '用户 ID', render: (row: PopupCampaignUserDetail) => row.userId },
    { title: '邮箱', render: (row: PopupCampaignUserDetail) => row.email },
    { title: '状态', render: (row: PopupCampaignUserDetail) => row.status },
    { title: '累计展示', render: (row: PopupCampaignUserDetail) => String(row.totalImpressions) },
    { title: '当日展示', render: (row: PopupCampaignUserDetail) => String(row.dailyImpressions) },
    { title: '最近展示', render: (row: PopupCampaignUserDetail) => formatDateTime(row.lastImpressionAt) },
    { title: '不再提醒', render: (row: PopupCampaignUserDetail) => formatDateTime(row.optedOutAt) },
    { title: '最近点击', render: (row: PopupCampaignUserDetail) => formatDateTime(row.lastClickedAt) },
    { title: '领取/展示/关闭/点击', render: (row: PopupCampaignUserDetail) => (
      `${row.issuedDeliveries}/${row.shownDeliveries}/${row.closedDeliveries}/${row.clickedDeliveries}`
    ) }
  ]
}
