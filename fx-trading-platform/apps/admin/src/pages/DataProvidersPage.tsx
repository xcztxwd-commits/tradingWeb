import { FormEvent, useState } from 'react'

import {
  createDataProvider,
  getDataProviders,
  syncProviderInstruments,
  testDataProvider,
  updateDataProvider
} from '../services/adminApi'
import { getValidAdminToken } from '../services/adminToken'
import type { DataProviderPayload, DataProviderRow } from '../types'
import { DataTable, display, formatDateTime, PageHeader, StateBlock, useAdminData } from './adminPageUtils'

type ProviderForm = {
  id: string
  code: string
  name: string
  providerType: string
  assetClasses: string
  restBaseUrl: string
  wsUrl: string
  enabled: boolean
  priority: number
  timeoutMs: number
  rateLimitPerMinute: number
  configJson: string
}

const emptyProviderForm: ProviderForm = {
  id: '',
  code: '',
  name: '',
  providerType: 'REST',
  assetClasses: 'FOREX',
  restBaseUrl: '',
  wsUrl: '',
  enabled: true,
  priority: 100,
  timeoutMs: 5000,
  rateLimitPerMinute: 1200,
  configJson: '{}'
}

export function DataProvidersPage() {
  const { data, loading, error, reload } = useAdminData(getDataProviders)
  const [form, setForm] = useState<ProviderForm>(emptyProviderForm)
  const [message, setMessage] = useState('')
  const [actionError, setActionError] = useState('')

  const saveProvider = async (event: FormEvent) => {
    event.preventDefault()
    await runAction(async (token) => {
      const payload = toProviderPayload(form)
      if (form.id) {
        await updateDataProvider(token, form.id, payload)
      } else {
        await createDataProvider(token, payload)
      }
      setForm(emptyProviderForm)
      setMessage('数据源已保存')
      await reload()
    })
  }

  const testProvider = (providerId: string) => runAction(async (token) => {
    const provider = await testDataProvider(token, providerId)
    setMessage(`${provider.code} health: ${provider.healthStatus}`)
    await reload()
  })

  const syncProvider = (providerId: string) => runAction(async (token) => {
    const result = await syncProviderInstruments(token, providerId)
    setMessage(`已同步 ${result.syncedCount} 个品种`)
  })

  const runAction = async (handler: (token: string) => Promise<void>) => {
    const token = getValidAdminToken()
    if (!token) {
      setActionError('登录状态已失效，请重新登录')
      return
    }
    setActionError('')
    setMessage('')
    try {
      await handler(token)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '操作失败')
    }
  }

  return (
    <>
      <PageHeader title="行情数据源" description="配置 Massive、Binance、OKX、Demo 等数据源，并执行健康检查和品种同步。" />
      {message ? <div className="feature-message">{message}</div> : null}
      {actionError ? <StateBlock tone="error">{actionError}</StateBlock> : null}

      <form className="admin-config-form" onSubmit={saveProvider}>
        <div className="admin-form-grid">
          <label>
            Code
            <input value={form.code} disabled={Boolean(form.id)} onChange={(event) => updateForm('code', event.target.value)} />
          </label>
          <label>
            Name
            <input value={form.name} onChange={(event) => updateForm('name', event.target.value)} />
          </label>
          <label>
            Type
            <select value={form.providerType} onChange={(event) => updateForm('providerType', event.target.value)}>
              <option value="REST">REST</option>
              <option value="REST_WS">REST_WS</option>
              <option value="WS">WS</option>
              <option value="LOCAL">LOCAL</option>
              <option value="COMPOSITE">COMPOSITE</option>
            </select>
          </label>
          <label>
            Asset Classes
            <input value={form.assetClasses} onChange={(event) => updateForm('assetClasses', event.target.value)} />
          </label>
          <label>
            REST Base URL
            <input value={form.restBaseUrl} onChange={(event) => updateForm('restBaseUrl', event.target.value)} />
          </label>
          <label>
            WS URL
            <input value={form.wsUrl} onChange={(event) => updateForm('wsUrl', event.target.value)} />
          </label>
          <label>
            Priority
            <input type="number" value={form.priority} onChange={(event) => updateForm('priority', Number(event.target.value))} />
          </label>
          <label>
            Timeout MS
            <input type="number" value={form.timeoutMs} onChange={(event) => updateForm('timeoutMs', Number(event.target.value))} />
          </label>
          <label>
            Rate Limit / Min
            <input
              type="number"
              value={form.rateLimitPerMinute}
              onChange={(event) => updateForm('rateLimitPerMinute', Number(event.target.value))}
            />
          </label>
          <label className="admin-checkbox-field">
            <input type="checkbox" checked={form.enabled} onChange={(event) => updateForm('enabled', event.target.checked)} />
            Enabled
          </label>
          <label className="admin-field-wide">
            Config JSON
            <textarea value={form.configJson} rows={3} onChange={(event) => updateForm('configJson', event.target.value)} />
          </label>
        </div>
        <div className="admin-action-row">
          <button type="submit">{form.id ? '保存数据源' : '新增数据源'}</button>
          <button type="button" onClick={() => setForm(emptyProviderForm)}>清空</button>
        </div>
      </form>

      {loading ? <StateBlock tone="loading">正在加载数据源</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error ? (
        <DataTable
          rows={data ?? []}
          emptyText="暂无数据源"
          columns={[
            { title: 'Code', render: (row) => row.code },
            { title: 'Name', render: (row) => row.name },
            { title: 'Asset Classes', render: (row) => row.assetClasses.join(', ') },
            { title: 'Capabilities', render: (row) => row.capabilities.join(', ') || '-' },
            { title: 'Health', render: (row) => row.healthStatus },
            { title: 'Enabled', render: (row) => display(row.enabled) },
            { title: 'Last Check', render: (row) => formatDateTime(row.lastHealthCheckAt) },
            {
              title: 'Actions',
              render: (row) => (
                <div className="admin-inline-actions">
                  <button type="button" onClick={() => setForm(providerToForm(row))}>编辑</button>
                  <button type="button" onClick={() => void testProvider(row.id)}>测试</button>
                  <button type="button" onClick={() => void syncProvider(row.id)}>同步</button>
                </div>
              )
            }
          ]}
        />
      ) : null}
    </>
  )

  function updateForm<K extends keyof ProviderForm>(key: K, value: ProviderForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }
}

function providerToForm(row: DataProviderRow): ProviderForm {
  return {
    id: row.id,
    code: row.code,
    name: row.name,
    providerType: row.providerType,
    assetClasses: row.assetClasses.join(','),
    restBaseUrl: row.restBaseUrl ?? '',
    wsUrl: row.wsUrl ?? '',
    enabled: row.enabled,
    priority: row.priority,
    timeoutMs: row.timeoutMs,
    rateLimitPerMinute: row.rateLimitPerMinute,
    configJson: row.configJson || '{}'
  }
}

function toProviderPayload(form: ProviderForm): DataProviderPayload {
  return {
    code: form.code.trim(),
    name: form.name.trim(),
    providerType: form.providerType,
    assetClasses: splitCsv(form.assetClasses),
    restBaseUrl: blankToNull(form.restBaseUrl),
    wsUrl: blankToNull(form.wsUrl),
    enabled: form.enabled,
    priority: form.priority,
    timeoutMs: form.timeoutMs,
    rateLimitPerMinute: form.rateLimitPerMinute,
    configJson: form.configJson.trim() || '{}'
  }
}

function splitCsv(value: string) {
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}

function blankToNull(value: string) {
  const text = value.trim()
  return text ? text : null
}
