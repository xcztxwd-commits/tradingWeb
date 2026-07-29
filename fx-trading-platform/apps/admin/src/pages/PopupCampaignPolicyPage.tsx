import { type FormEvent, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'

import { getPopupPolicy, updatePopupPolicy } from '../services/engagementAdminApi'
import { getAdminAuthorities, getValidAdminToken } from '../services/adminToken'
import { PageHeader, StateBlock, useAdminData } from './adminPageUtils'

export function PopupCampaignPolicyPage() {
  const authorities = useMemo(() => new Set(getAdminAuthorities()), [])
  const canUpdate = authorities.has('content:popup-policy:update')
  const { data, loading, error, reload } = useAdminData(getPopupPolicy)
  const [maxSequentialPopups, setMaxSequentialPopups] = useState(3)
  const [deliveryRetentionDays, setDeliveryRetentionDays] = useState(365)
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState('')
  const [message, setMessage] = useState('')

  useEffect(() => {
    if (!data) return
    setMaxSequentialPopups(data.maxSequentialPopups)
    setDeliveryRetentionDays(data.deliveryRetentionDays)
  }, [data])

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (!canUpdate) return
    if (!reason.trim()) {
      setActionError('修改原因不能为空')
      return
    }
    const token = getValidAdminToken()
    if (!token) {
      setActionError('登录状态已失效，请重新登录')
      return
    }
    setSaving(true)
    setActionError('')
    setMessage('')
    try {
      await updatePopupPolicy(token, {
        maxSequentialPopups,
        deliveryRetentionDays,
        reason: reason.trim()
      })
      setReason('')
      setMessage('全局弹窗策略已更新')
      await reload()
    } catch (caught) {
      setActionError(caught instanceof Error ? caught.message : '策略更新失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="feature-page">
      <PageHeader
        title="全局弹窗策略"
        description="控制每个领取会话的连续弹窗上限，以及原始投放明细的保留天数。"
      />
      <div className="feature-toolbar">
        <div className="feature-toolbar-left">
          <Link className="ghost-button" to="/content/popup-campaigns">返回活动列表</Link>
        </div>
      </div>

      {loading ? <StateBlock tone="loading">正在加载全局策略...</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error && !data ? <StateBlock>未找到全局弹窗策略</StateBlock> : null}
      {message ? <div className="feature-message">{message}</div> : null}
      {actionError ? <StateBlock tone="error">{actionError}</StateBlock> : null}

      {!loading && !error && data ? (
        <form className="admin-config-form" onSubmit={save}>
          <div className="admin-form-grid">
            <label>
              连续弹窗上限
              <input
                type="number"
                min={1}
                max={100}
                required
                disabled={!canUpdate || saving}
                value={maxSequentialPopups}
                onChange={(event) => setMaxSequentialPopups(Number(event.target.value))}
              />
              <span className="admin-field-hint">maxSequentialPopups，范围 1–100。</span>
            </label>
            <label>
              原始明细保留天数
              <input
                type="number"
                min={1}
                max={3650}
                required
                disabled={!canUpdate || saving}
                value={deliveryRetentionDays}
                onChange={(event) => setDeliveryRetentionDays(Number(event.target.value))}
              />
              <span className="admin-field-hint">deliveryRetentionDays，范围 1–3650 天。</span>
            </label>
            {canUpdate ? (
              <label className="admin-field-wide">
                修改原因
                <input
                  value={reason}
                  maxLength={500}
                  required
                  disabled={saving}
                  onChange={(event) => setReason(event.target.value)}
                />
              </label>
            ) : null}
          </div>
          <div className="admin-action-row">
            {canUpdate ? <button type="submit" disabled={saving}>{saving ? '保存中...' : '保存策略'}</button> : null}
          </div>
          {!canUpdate ? <StateBlock>当前管理员只有查看权限，不能修改全局策略。</StateBlock> : null}
        </form>
      ) : null}
    </section>
  )
}
