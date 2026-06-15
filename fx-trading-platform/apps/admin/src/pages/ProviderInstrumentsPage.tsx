import { useEffect, useMemo, useState } from 'react'

import { getDataProviders, getProviderInstruments } from '../services/adminApi'
import { getValidAdminToken } from '../services/adminToken'
import type { ProviderInstrumentRow } from '../types'
import { DataTable, display, formatDateTime, PageHeader, StateBlock, useAdminData } from './adminPageUtils'

export function ProviderInstrumentsPage() {
  const providers = useAdminData(getDataProviders)
  const [providerId, setProviderId] = useState('')
  const [rows, setRows] = useState<ProviderInstrumentRow[]>([])
  const [assetClassFilter, setAssetClassFilter] = useState('')
  const [instrumentSearch, setInstrumentSearch] = useState('')
  const [loadingRows, setLoadingRows] = useState(false)
  const [rowError, setRowError] = useState('')

  useEffect(() => {
    if (!providerId && providers.data?.length) {
      setProviderId(providers.data[0].id)
    }
  }, [providerId, providers.data])

  useEffect(() => {
    if (!providerId) {
      setRows([])
      return
    }
    void loadRows(providerId)
  }, [providerId])

  const selectedProvider = providers.data?.find((provider) => provider.id === providerId)
  const assetClassOptions = useMemo(
    () => Array.from(new Set(rows.map((row) => row.assetClass))).sort(),
    [rows]
  )
  const filteredRows = useMemo(() => {
    const normalizedSearch = instrumentSearch.trim().toLowerCase()
    return rows.filter((row) => {
      const matchesAssetClass = !assetClassFilter || row.assetClass === assetClassFilter
      const searchText = [
        row.providerSymbol,
        row.displayName ?? '',
        row.baseAsset ?? '',
        row.quoteAsset ?? ''
      ].join(' ').toLowerCase()
      return matchesAssetClass && (!normalizedSearch || searchText.includes(normalizedSearch))
    })
  }, [assetClassFilter, instrumentSearch, rows])

  useEffect(() => {
    if (assetClassFilter && !assetClassOptions.includes(assetClassFilter)) {
      setAssetClassFilter('')
    }
  }, [assetClassFilter, assetClassOptions])

  return (
    <>
      <PageHeader title="数据源品种" description="查看从某个行情源同步来的原始 provider symbol，用于后续绑定平台交易品种。" />
      {providers.loading ? <StateBlock tone="loading">正在加载数据源</StateBlock> : null}
      {providers.error ? <StateBlock tone="error">{providers.error}</StateBlock> : null}
      {!providers.loading && !providers.error ? (
        <section className="admin-config-form">
          <div className="admin-form-grid">
            <label>
              Provider
              <select value={providerId} onChange={(event) => setProviderId(event.target.value)}>
                {(providers.data ?? []).map((provider) => (
                  <option key={provider.id} value={provider.id}>{provider.code} - {provider.name}</option>
                ))}
              </select>
            </label>
            <label>
              Health
              <input value={selectedProvider?.healthStatus ?? '-'} readOnly />
            </label>
            <label>
              Asset Classes
              <input value={selectedProvider?.assetClasses.join(', ') ?? '-'} readOnly />
            </label>
            <label>
              Asset Class Filter
              <select value={assetClassFilter} onChange={(event) => setAssetClassFilter(event.target.value)}>
                <option value="">全部资产</option>
                {assetClassOptions.map((assetClass) => (
                  <option key={assetClass} value={assetClass}>{assetClass}</option>
                ))}
              </select>
            </label>
            <label>
              Search
              <input
                value={instrumentSearch}
                placeholder="搜索 provider symbol/base/quote/name"
                onChange={(event) => setInstrumentSearch(event.target.value)}
              />
            </label>
          </div>
        </section>
      ) : null}

      {loadingRows ? <StateBlock tone="loading">正在加载 provider instruments</StateBlock> : null}
      {rowError ? <StateBlock tone="error">{rowError}</StateBlock> : null}
      {!loadingRows && !rowError ? (
        <DataTable
          rows={filteredRows}
          emptyText="暂无同步品种"
          columns={[
            { title: 'Provider Symbol', render: (row) => row.providerSymbol },
            { title: 'Name', render: (row) => row.displayName ?? '-' },
            { title: 'Asset Class', render: (row) => row.assetClass },
            { title: 'Base', render: (row) => row.baseAsset ?? '-' },
            { title: 'Quote', render: (row) => row.quoteAsset ?? '-' },
            { title: 'Listed', render: (row) => display(row.listed) },
            { title: 'Last Synced', render: (row) => formatDateTime(row.lastSyncedAt) }
          ]}
        />
      ) : null}
    </>
  )

  async function loadRows(nextProviderId: string) {
    const token = getValidAdminToken()
    if (!token) {
      setRowError('登录状态已失效，请重新登录')
      return
    }
    setLoadingRows(true)
    setRowError('')
    try {
      setRows(await getProviderInstruments(token, nextProviderId))
    } catch (err) {
      setRowError(err instanceof Error ? err.message : '加载 provider instruments 失败')
    } finally {
      setLoadingRows(false)
    }
  }
}
