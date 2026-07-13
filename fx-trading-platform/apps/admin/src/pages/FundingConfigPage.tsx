import { type FormEvent, useState } from 'react'
import type { AdminFundingConfigRequest, AdminFundingConfigResponse } from '@fx-platform/shared-types'

import { getSymbolsPage } from '../services/adminApi'
import { getFundingConfig, loadAllAdminPages, updateFundingConfig } from '../services/adminTradingApi'
import { getValidAdminToken } from '../services/adminToken'
import { DataTable, display, formatDateTime, PageHeader, StateBlock, useAdminData } from './adminPageUtils'
import { formatFundingFallbackState } from './fundingFallbackModel'

type FundingConfigForm = {
  fundingSourcePriority: string
  fixedFundingRate: string
  fixedFundingIntervalMinutes: string
  fundingStaleSeconds: string
  reason: string
}

const emptyForm: FundingConfigForm = {
  fundingSourcePriority: '',
  fixedFundingRate: '',
  fixedFundingIntervalMinutes: '',
  fundingStaleSeconds: '',
  reason: ''
}

export function FundingConfigPage() {
  const { data, loading, error, reload } = useAdminData(loadFundingConfigs)
  const [editing, setEditing] = useState<AdminFundingConfigResponse>()
  const [form, setForm] = useState<FundingConfigForm>(emptyForm)
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState('')
  const [message, setMessage] = useState('')

  const startEditing = (config: AdminFundingConfigResponse) => {
    setEditing(config)
    setForm({
      fundingSourcePriority: (config.fundingSourcePriority ?? []).join(', '),
      fixedFundingRate: String(config.fixedFundingRate ?? ''),
      fixedFundingIntervalMinutes: String(config.fixedFundingIntervalMinutes ?? ''),
      fundingStaleSeconds: String(config.fundingStaleSeconds ?? ''),
      reason: ''
    })
    setActionError('')
    setMessage('')
  }

  const saveConfig = async (event: FormEvent) => {
    event.preventDefault()
    const token = getValidAdminToken()
    if (!token || !editing?.symbolId) {
      setActionError('管理员登录状态已失效，请重新登录')
      return
    }
    const payload = toFundingConfigRequest(form)
    if (!payload) {
      setActionError('请填写有序来源、有效数值和必填原因')
      return
    }

    setSaving(true)
    setActionError('')
    setMessage('')
    try {
      await updateFundingConfig(editing.symbolId, payload, token)
      setMessage(`${editing.symbol ?? editing.symbolId} 资金费配置已更新`)
      closeEditor()
      await reload()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '资金费配置更新失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <PageHeader
        title="资金费配置"
        description="管理每个 LINEAR_PERP 品种的有序来源、固定费率、周期和过期阈值。"
      />
      {message ? <div className="feature-message">{message}</div> : null}
      {actionError ? <StateBlock tone="error">{actionError}</StateBlock> : null}

      {editing ? (
        <form className="admin-config-form" onSubmit={saveConfig}>
          <h3>编辑 {display(editing.symbol)}</h3>
          <div className="admin-form-grid">
            <label>
              有序来源（逗号分隔）
              <input
                value={form.fundingSourcePriority}
                onChange={(event) => updateForm('fundingSourcePriority', event.target.value)}
                placeholder="BINANCE, OKX, FIXED"
              />
            </label>
            <label>
              固定费率
              <input
                type="number"
                step="any"
                value={form.fixedFundingRate}
                onChange={(event) => updateForm('fixedFundingRate', event.target.value)}
              />
            </label>
            <label>
              资金费周期（分钟）
              <input
                type="number"
                min="1"
                value={form.fixedFundingIntervalMinutes}
                onChange={(event) => updateForm('fixedFundingIntervalMinutes', event.target.value)}
              />
            </label>
            <label>
              数据过期阈值（秒）
              <input
                type="number"
                min="1"
                value={form.fundingStaleSeconds}
                onChange={(event) => updateForm('fundingStaleSeconds', event.target.value)}
              />
            </label>
            <label className="admin-field-wide">
              更新原因（必填）
              <textarea value={form.reason} rows={3} onChange={(event) => updateForm('reason', event.target.value)} />
            </label>
          </div>
          <div className="admin-action-row">
            <button type="submit" disabled={saving}>{saving ? '保存中…' : '保存配置'}</button>
            <button type="button" disabled={saving} onClick={closeEditor}>取消</button>
          </div>
        </form>
      ) : null}

      {loading ? <StateBlock tone="loading">正在并行加载资金费配置</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error ? (
        <DataTable
          rows={data ?? []}
          emptyText="暂无 LINEAR_PERP 资金费配置"
          columns={[
            { title: '品种', render: (row) => display(row.symbol) },
            { title: '有序来源', render: (row) => (row.fundingSourcePriority ?? []).join(' → ') || '-' },
            { title: '固定费率', render: (row) => display(row.fixedFundingRate) },
            { title: '周期（分钟）', render: (row) => display(row.fixedFundingIntervalMinutes) },
            { title: '过期阈值（秒）', render: (row) => display(row.fundingStaleSeconds) },
            { title: '实际选择源', render: (row) => display(row.actualSource) },
            { title: '回退状态 / 原因', render: (row) => formatFundingFallbackState(row) },
            { title: '上次 asOf', render: (row) => formatDateTime(row.asOf) },
            { title: '下次资金费时间', render: (row) => formatDateTime(row.nextFundingTime) },
            { title: '操作', render: (row) => <button type="button" onClick={() => startEditing(row)}>编辑</button> }
          ]}
        />
      ) : null}
    </>
  )

  function updateForm<K extends keyof FundingConfigForm>(key: K, value: FundingConfigForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  function closeEditor() {
    setEditing(undefined)
    setForm(emptyForm)
  }
}

async function loadFundingConfigs(token: string): Promise<AdminFundingConfigResponse[]> {
  const symbols = await loadAllAdminPages((page, size) => getSymbolsPage(token, page, size))
  const perpetualSymbols = symbols.filter((symbol) => symbol.productType === 'LINEAR_PERP')
  return Promise.all(perpetualSymbols.map((symbol) => getFundingConfig(symbol.id, token)))
}

function toFundingConfigRequest(form: FundingConfigForm): AdminFundingConfigRequest | null {
  const fundingSourcePriority = form.fundingSourcePriority
    .split(',')
    .map((source) => source.trim().toUpperCase())
    .filter(Boolean)
  const fixedFundingRate = Number(form.fixedFundingRate)
  const fixedFundingIntervalMinutes = Number(form.fixedFundingIntervalMinutes)
  const fundingStaleSeconds = Number(form.fundingStaleSeconds)
  const reason = form.reason.trim()
  if (
    fundingSourcePriority.length === 0 ||
    !form.fixedFundingRate.trim() ||
    !Number.isFinite(fixedFundingRate) ||
    !Number.isInteger(fixedFundingIntervalMinutes) ||
    fixedFundingIntervalMinutes < 1 ||
    !Number.isInteger(fundingStaleSeconds) ||
    fundingStaleSeconds < 1 ||
    !reason
  ) {
    return null
  }
  return {
    fundingSourcePriority,
    fixedFundingRate,
    fixedFundingIntervalMinutes,
    fundingStaleSeconds,
    reason
  }
}
