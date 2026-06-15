import { FormEvent, useEffect, useMemo, useState } from 'react'

import {
  createSymbolProviderBinding,
  getDataProviders,
  getProviderInstruments,
  getSymbolProviderBindings,
  getSymbolsPage,
  updateSymbolDisplay,
  updateSymbolProviderBinding
} from '../services/adminApi'
import { getValidAdminToken } from '../services/adminToken'
import type {
  DataProviderRow,
  ProviderInstrumentRow,
  SymbolDisplayPayload,
  SymbolProviderBindingPayload,
  SymbolProviderBindingRow,
  SymbolRow
} from '../types'
import { DataTable, display, PageHeader, StateBlock, useAdminData } from './adminPageUtils'

type BindingForm = {
  bindingId: string
  providerId: string
  providerInstrumentId: string
  providerSymbol: string
  priority: number
  enabled: boolean
  configJson: string
}

const emptyBindingForm: BindingForm = {
  bindingId: '',
  providerId: '',
  providerInstrumentId: '',
  providerSymbol: '',
  priority: 100,
  enabled: true,
  configJson: '{}'
}

const emptyDisplayForm: SymbolDisplayPayload = {
  iconUrl: null,
  displayEnabled: true,
  quoteEnabled: true,
  chartEnabled: true,
  orderBookEnabled: true,
  tradable: true,
  featured: false,
  displayGroup: null,
  displayOrder: 0
}

export function SymbolDataBindingsPage() {
  const symbols = useAdminData((token) => getSymbolsPage(token, 0, 500))
  const providers = useAdminData(getDataProviders)
  const [symbolId, setSymbolId] = useState('')
  const [bindings, setBindings] = useState<SymbolProviderBindingRow[]>([])
  const [providerInstruments, setProviderInstruments] = useState<ProviderInstrumentRow[]>([])
  const [bindingForm, setBindingForm] = useState<BindingForm>(emptyBindingForm)
  const [displayForm, setDisplayForm] = useState<SymbolDisplayPayload>(emptyDisplayForm)
  const [loadingBindings, setLoadingBindings] = useState(false)
  const [loadingProviderInstruments, setLoadingProviderInstruments] = useState(false)
  const [providerInstrumentError, setProviderInstrumentError] = useState('')
  const [message, setMessage] = useState('')
  const [actionError, setActionError] = useState('')

  const symbolRows = symbols.data?.items ?? []
  const selectedSymbol = useMemo(
    () => symbolRows.find((symbol) => symbol.id === symbolId),
    [symbolId, symbolRows]
  )
  const providerRows = providers.data ?? []
  const iconDisplaySource = selectedSymbol ? describeIconDisplaySource(selectedSymbol, displayForm.iconUrl) : ''

  useEffect(() => {
    if (!symbolId && symbolRows.length) {
      setSymbolId(symbolRows[0].id)
    }
  }, [symbolId, symbolRows])

  useEffect(() => {
    if (!bindingForm.providerId && providerRows.length) {
      setBindingForm((current) => ({ ...current, providerId: providerRows[0].id }))
    }
  }, [bindingForm.providerId, providerRows])

  useEffect(() => {
    if (!selectedSymbol) return
    setDisplayForm(symbolToDisplayPayload(selectedSymbol))
    setBindingForm((current) => ({
      ...emptyBindingForm,
      providerId: current.providerId || providerRows[0]?.id || '',
      providerSymbol: selectedSymbol.providerSymbol || selectedSymbol.symbol
    }))
    void loadBindings(selectedSymbol.id)
  }, [selectedSymbol, providerRows])

  useEffect(() => {
    if (!bindingForm.providerId) {
      setProviderInstruments([])
      return
    }
    void loadProviderInstruments(bindingForm.providerId)
  }, [bindingForm.providerId])

  const saveDisplay = async (event: FormEvent) => {
    event.preventDefault()
    if (!selectedSymbol) return
    await runAction(async (token) => {
      await updateSymbolDisplay(token, selectedSymbol.id, displayForm)
      setMessage('品种展示开关已保存')
      await symbols.reload()
    })
  }

  const saveBinding = async (event: FormEvent) => {
    event.preventDefault()
    if (!selectedSymbol) return
    if (!bindingForm.providerId) {
      setActionError('请选择 Provider')
      return
    }
    if (!bindingForm.providerSymbol.trim()) {
      setActionError('请输入 Provider Symbol')
      return
    }
    await runAction(async (token) => {
      const payload = toBindingPayload(bindingForm)
      if (bindingForm.bindingId) {
        await updateSymbolProviderBinding(token, selectedSymbol.id, bindingForm.bindingId, payload)
      } else {
        await createSymbolProviderBinding(token, selectedSymbol.id, payload)
      }
      setBindingForm({ ...emptyBindingForm, providerId: bindingForm.providerId })
      setMessage('数据源绑定已保存')
      await loadBindings(selectedSymbol.id)
    })
  }

  return (
    <>
      <PageHeader title="品种数据源绑定" description="给每个交易品种单独指定行情源、provider symbol 和优先级，支持 FOREX/CRYPTO 分开配置。" />
      {symbols.loading || providers.loading ? <StateBlock tone="loading">正在加载品种和数据源</StateBlock> : null}
      {symbols.error ? <StateBlock tone="error">{symbols.error}</StateBlock> : null}
      {providers.error ? <StateBlock tone="error">{providers.error}</StateBlock> : null}
      {message ? <div className="feature-message">{message}</div> : null}
      {actionError ? <StateBlock tone="error">{actionError}</StateBlock> : null}

      {!symbols.loading && !providers.loading && !symbols.error && !providers.error ? (
        <>
          <section className="admin-config-form">
            <div className="admin-form-grid">
              <label>
                Symbol
                <select value={symbolId} onChange={(event) => setSymbolId(event.target.value)}>
                  {symbolRows.map((symbol) => (
                    <option key={symbol.id} value={symbol.id}>
                      {symbol.symbol} - {symbol.assetClass}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Current Provider
                <input value={selectedSymbol?.provider ?? '-'} readOnly />
              </label>
              <label>
                Current Provider Symbol
                <input value={selectedSymbol?.providerSymbol ?? '-'} readOnly />
              </label>
            </div>
          </section>

          <form className="admin-config-form" onSubmit={saveDisplay}>
            <h3>展示和功能开关</h3>
            <div className="admin-form-grid">
              <label>
                Icon URL
                <input
                  value={displayForm.iconUrl ?? ''}
                  aria-describedby="symbol-icon-url-source"
                  onChange={(event) => updateDisplay('iconUrl', blankToNull(event.target.value))}
                />
                {iconDisplaySource ? (
                  <span id="symbol-icon-url-source" className="admin-field-hint">
                    {iconDisplaySource}
                  </span>
                ) : null}
              </label>
              <label>
                Display Group
                <input
                  value={displayForm.displayGroup ?? ''}
                  onChange={(event) => updateDisplay('displayGroup', blankToNull(event.target.value))}
                />
              </label>
              <label>
                Display Order
                <input
                  type="number"
                  value={displayForm.displayOrder}
                  onChange={(event) => updateDisplay('displayOrder', Number(event.target.value))}
                />
              </label>
              {([
                ['displayEnabled', 'Display'],
                ['quoteEnabled', 'Quote'],
                ['chartEnabled', 'Chart'],
                ['orderBookEnabled', 'Order Book'],
                ['tradable', 'Tradable'],
                ['featured', 'Featured']
              ] as const).map(([key, label]) => (
                <label key={key} className="admin-checkbox-field">
                  <input
                    type="checkbox"
                    checked={Boolean(displayForm[key])}
                    onChange={(event) => updateDisplay(key, event.target.checked)}
                  />
                  {label}
                </label>
              ))}
            </div>
            <div className="admin-action-row">
              <button type="submit" disabled={!selectedSymbol}>保存展示配置</button>
            </div>
          </form>

          <form className="admin-config-form" onSubmit={saveBinding}>
            <h3>{bindingForm.bindingId ? '编辑数据源绑定' : '新增数据源绑定'}</h3>
            <div className="admin-form-grid">
              <label>
                Provider
                <select value={bindingForm.providerId} onChange={(event) => handleProviderChange(event.target.value)}>
                  {providerRows.map((provider) => (
                    <option key={provider.id} value={provider.id}>
                      {provider.code} - {provider.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Provider Instrument
                <select value={bindingForm.providerInstrumentId} onChange={(event) => handleProviderInstrumentChange(event.target.value)}>
                  <option value="">不关联 instrument（手动填写）</option>
                  {providerInstruments.map((instrument) => (
                    <option key={instrument.id} value={instrument.id}>
                      {instrument.providerSymbol} - {instrument.displayName ?? instrument.assetClass}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Provider Symbol
                <input value={bindingForm.providerSymbol} onChange={(event) => updateBinding('providerSymbol', event.target.value)} />
              </label>
              <label>
                Priority
                <input type="number" value={bindingForm.priority} onChange={(event) => updateBinding('priority', Number(event.target.value))} />
              </label>
              <label className="admin-checkbox-field">
                <input type="checkbox" checked={bindingForm.enabled} onChange={(event) => updateBinding('enabled', event.target.checked)} />
                Enabled
              </label>
              <label className="admin-field-wide">
                Config JSON
                <textarea value={bindingForm.configJson} rows={3} onChange={(event) => updateBinding('configJson', event.target.value)} />
              </label>
            </div>
            <div className="admin-action-row">
              <button type="submit" disabled={!selectedSymbol || !bindingForm.providerId}>保存绑定</button>
              <button type="button" onClick={() => resetBindingForm()}>
                清空
              </button>
            </div>
          </form>

          {loadingProviderInstruments ? <StateBlock tone="loading">正在加载 provider instruments</StateBlock> : null}
          {providerInstrumentError ? <StateBlock tone="error">{providerInstrumentError}</StateBlock> : null}
          {loadingBindings ? <StateBlock tone="loading">正在加载绑定</StateBlock> : null}
          {!loadingBindings ? (
            <DataTable
              rows={bindings}
              emptyText="当前品种暂无 provider binding"
              columns={[
                { title: 'Provider', render: (row) => providerLabel(row.providerId, providerRows) },
                { title: 'Provider Symbol', render: (row) => row.providerSymbol },
                { title: 'Priority', render: (row) => row.priority },
                { title: 'Enabled', render: (row) => display(row.enabled) },
                { title: 'Config', render: (row) => row.configJson || '{}' },
                {
                  title: 'Actions',
                  render: (row) => (
                    <button type="button" onClick={() => setBindingForm(bindingToForm(row))}>
                      编辑
                    </button>
                  )
                }
              ]}
            />
          ) : null}
        </>
      ) : null}
    </>
  )

  function updateDisplay<K extends keyof SymbolDisplayPayload>(key: K, value: SymbolDisplayPayload[K]) {
    setDisplayForm((current) => ({ ...current, [key]: value }))
  }

  function updateBinding<K extends keyof BindingForm>(key: K, value: BindingForm[K]) {
    setBindingForm((current) => ({ ...current, [key]: value }))
  }

  function handleProviderChange(providerId: string) {
    setBindingForm((current) => ({
      ...current,
      providerId,
      providerInstrumentId: '',
      providerSymbol: selectedSymbol?.providerSymbol || selectedSymbol?.symbol || current.providerSymbol
    }))
  }

  function handleProviderInstrumentChange(providerInstrumentId: string) {
    const instrument = providerInstruments.find((item) => item.id === providerInstrumentId)
    setBindingForm((current) => ({
      ...current,
      providerInstrumentId,
      providerSymbol: instrument?.providerSymbol ?? current.providerSymbol
    }))
  }

  function resetBindingForm() {
    setBindingForm({
      ...emptyBindingForm,
      providerId: bindingForm.providerId || providerRows[0]?.id || '',
      providerSymbol: selectedSymbol?.providerSymbol || selectedSymbol?.symbol || ''
    })
  }

  async function runAction(handler: (token: string) => Promise<void>) {
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

  async function loadBindings(nextSymbolId: string) {
    const token = getValidAdminToken()
    if (!token) {
      setActionError('登录状态已失效，请重新登录')
      return
    }
    setLoadingBindings(true)
    setActionError('')
    try {
      setBindings(await getSymbolProviderBindings(token, nextSymbolId))
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '加载绑定失败')
    } finally {
      setLoadingBindings(false)
    }
  }

  async function loadProviderInstruments(nextProviderId: string) {
    const token = getValidAdminToken()
    if (!token) {
      setProviderInstrumentError('登录状态已失效，请重新登录')
      return
    }
    setLoadingProviderInstruments(true)
    setProviderInstrumentError('')
    try {
      setProviderInstruments(await getProviderInstruments(token, nextProviderId))
    } catch (err) {
      setProviderInstrumentError(err instanceof Error ? err.message : '加载 provider instruments 失败')
    } finally {
      setLoadingProviderInstruments(false)
    }
  }
}

function symbolToDisplayPayload(symbol: SymbolRow): SymbolDisplayPayload {
  return {
    iconUrl: symbol.iconUrl ?? null,
    displayEnabled: symbol.displayEnabled ?? true,
    quoteEnabled: symbol.quoteEnabled ?? true,
    chartEnabled: symbol.chartEnabled ?? true,
    orderBookEnabled: symbol.orderBookEnabled ?? true,
    tradable: symbol.tradable ?? true,
    featured: symbol.featured ?? false,
    displayGroup: symbol.displayGroup ?? null,
    displayOrder: symbol.displayOrder ?? 0
  }
}

function bindingToForm(row: SymbolProviderBindingRow): BindingForm {
  return {
    bindingId: row.id,
    providerId: row.providerId,
    providerInstrumentId: row.providerInstrumentId ?? '',
    providerSymbol: row.providerSymbol,
    priority: row.priority,
    enabled: row.enabled,
    configJson: row.configJson || '{}'
  }
}

function toBindingPayload(form: BindingForm): SymbolProviderBindingPayload {
  return {
    providerId: form.providerId,
    providerInstrumentId: blankToNull(form.providerInstrumentId),
    providerSymbol: form.providerSymbol.trim(),
    priority: form.priority,
    enabled: form.enabled,
    configJson: form.configJson.trim() || '{}'
  }
}

function providerLabel(providerId: string, providers: DataProviderRow[]) {
  const provider = providers.find((item) => item.id === providerId)
  return provider ? `${provider.code} - ${provider.name}` : providerId
}

function describeIconDisplaySource(symbol: SymbolRow, iconUrl: string | null) {
  if (iconUrl?.trim()) {
    return '后台自定义 URL'
  }
  return isForexSymbol(symbol) ? '前端自动生成' : '未设置'
}

function isForexSymbol(symbol: SymbolRow) {
  const assetClass = symbol.assetClass.trim().toUpperCase()
  return assetClass === 'FOREX' || assetClass === 'FX'
}

function blankToNull(value: string) {
  const text = value.trim()
  return text ? text : null
}
