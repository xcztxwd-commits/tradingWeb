import { FormEvent, useState } from 'react'

import {
  createDataProvider,
  getDataProviders,
  syncProviderInstruments,
  testDataProvider,
  updateDataProvider
} from '../services/adminApi'
import { getAdminAuthorities, getValidAdminToken } from '../services/adminToken'
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
  const canUpdateProvider = getAdminAuthorities().includes('market:data-provider:update')

  const saveProvider = async (event: FormEvent) => {
    event.preventDefault()
    if (!canUpdateProvider) {
      setActionError('Missing market:data-provider:update')
      return
    }
    await runAction(async (token) => {
      let confirmationText: string | undefined
      if (providerStatusChanged(form, data ?? [])) {
        const entered = window.prompt('CONFIRM_PROVIDER_STATUS')
        if (entered !== 'CONFIRM_PROVIDER_STATUS') {
          setActionError('Provider status change confirmation failed')
          return
        }
        confirmationText = entered
      }
      const payload = toProviderPayload(form, confirmationText)
      if (form.id) {
        await updateDataProvider(token, form.id, payload)
      } else {
        await createDataProvider(token, payload)
      }
      setForm(emptyProviderForm)
      setMessage('Provider saved')
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
    setMessage(`Synced ${result.syncedCount} instruments`)
    await reload()
  })

  const runAction = async (handler: (token: string) => Promise<void>) => {
    const token = getValidAdminToken()
    if (!token) {
      setActionError('Admin session expired. Please sign in again.')
      return
    }
    setActionError('')
    setMessage('')
    try {
      await handler(token)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Action failed')
    }
  }

  return (
    <>
      <PageHeader
        title="Market Data Providers"
        description="Configure Massive, Binance, OKX, Demo providers and review quote health."
      />
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
          {canUpdateProvider ? <button type="submit">{form.id ? 'Save Provider' : 'Add Provider'}</button> : null}
          <button type="button" onClick={() => setForm(emptyProviderForm)}>Clear</button>
        </div>
      </form>

      {loading ? <StateBlock tone="loading">Loading providers...</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error ? (
        <DataTable
          rows={data ?? []}
          emptyText="No providers"
          columns={[
            { title: 'Code', render: (row) => row.code },
            { title: 'Name', render: (row) => row.name },
            { title: 'Asset Classes', render: (row) => row.assetClasses.join(', ') },
            { title: 'Capabilities', render: (row) => row.capabilities.join(', ') || '-' },
            { title: 'Capability Status', render: (row) => formatCapabilityStatuses(row.capabilityStatuses) },
            { title: 'Health', render: (row) => row.healthStatus },
            { title: 'Enabled', render: (row) => display(row.enabled) },
            { title: 'Last Check', render: (row) => formatDateTime(row.lastHealthCheckAt) },
            { title: 'Last Success', render: (row) => formatDateTime(row.lastSuccessAt) },
            { title: 'Last Failure', render: (row) => formatDateTime(row.lastFailureAt) },
            { title: 'Failures', render: (row) => String(row.failureCount ?? 0) },
            { title: 'Avg Latency', render: (row) => formatMs(row.avgLatencyMs) },
            { title: 'Quote Success', render: (row) => formatDateTime(row.lastQuoteSuccessAt) },
            { title: 'Quote Staleness', render: (row) => formatMs(row.quoteStalenessMs) },
            { title: 'Last Sync', render: (row) => formatDateTime(row.lastInstrumentSyncAt) },
            { title: 'Synced', render: (row) => String(row.lastInstrumentSyncCount ?? 0) },
            {
              title: 'Actions',
              render: (row) => canUpdateProvider ? (
                <div className="admin-inline-actions">
                  <button type="button" onClick={() => setForm(providerToForm(row))}>Edit</button>
                  <button type="button" onClick={() => void testProvider(row.id)}>Test</button>
                  <button type="button" onClick={() => void syncProvider(row.id)}>Sync</button>
                </div>
              ) : '-'
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

function providerStatusChanged(form: ProviderForm, providers: DataProviderRow[]) {
  if (!form.id) return false
  const current = providers.find((provider) => provider.id === form.id)
  return current ? current.enabled !== form.enabled : false
}

function toProviderPayload(form: ProviderForm, confirmationText?: string): DataProviderPayload {
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
    configJson: form.configJson.trim() || '{}',
    confirmationText
  }
}

function splitCsv(value: string) {
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}

function blankToNull(value: string) {
  const text = value.trim()
  return text ? text : null
}

function formatMs(value: number | null | undefined) {
  return value == null ? '-' : `${value} ms`
}

function formatCapabilityStatuses(statuses: DataProviderRow['capabilityStatuses']) {
  if (!statuses || statuses.length === 0) return '-'
  return statuses.map((item) => `${item.capability}:${item.status}`).join(', ')
}
