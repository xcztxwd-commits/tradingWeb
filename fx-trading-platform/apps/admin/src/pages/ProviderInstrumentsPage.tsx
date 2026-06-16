import { useEffect, useMemo, useState } from 'react'

import {
  createSymbol,
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
  ProductType,
  ProviderInstrumentRow,
  SymbolDisplayPayload,
  SymbolPayload,
  SymbolProviderBindingPayload,
  SymbolProviderBindingRow,
  SymbolRow
} from '../types'
import { DataTable, display, formatDateTime, PageHeader, StateBlock, useAdminData } from './adminPageUtils'

export function ProviderInstrumentsPage() {
  const providers = useAdminData(getDataProviders)
  const [providerId, setProviderId] = useState('')
  const [rows, setRows] = useState<ProviderInstrumentRow[]>([])
  const [assetClassFilter, setAssetClassFilter] = useState('')
  const [instrumentSearch, setInstrumentSearch] = useState('')
  const [loadingRows, setLoadingRows] = useState(false)
  const [rowError, setRowError] = useState('')
  const [publishingId, setPublishingId] = useState('')
  const [publishMessage, setPublishMessage] = useState('')
  const [publishError, setPublishError] = useState('')

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
      {publishMessage ? <div className="feature-message">{publishMessage}</div> : null}
      {publishError ? <StateBlock tone="error">{publishError}</StateBlock> : null}
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
            { title: 'Last Synced', render: (row) => formatDateTime(row.lastSyncedAt) },
            {
              title: 'Actions',
              render: (row) => (
                <button
                  type="button"
                  disabled={Boolean(publishingId)}
                  onClick={() => void publishProviderInstrument(row)}
                >
                  {publishingId === row.id ? '发布中' : '发布为平台品种'}
                </button>
              )
            }
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

  async function publishProviderInstrument(instrument: ProviderInstrumentRow) {
    const token = getValidAdminToken()
    if (!token) {
      setPublishError('登录状态已失效，请重新登录')
      return
    }
    const provider = providerForInstrument(instrument, providers.data ?? [])
    const platformSymbol = derivePlatformSymbol(instrument)
    if (!platformSymbol) {
      setPublishError('无法从 provider symbol 推导平台品种代码')
      return
    }

    setPublishingId(instrument.id)
    setPublishMessage('')
    setPublishError('')
    try {
      let symbol = await findPlatformSymbol(token, platformSymbol)
      const created = !symbol
      if (!symbol) {
        symbol = await createSymbol(token, toSymbolPayload(instrument, provider, platformSymbol))
      }

      await updateSymbolDisplay(token, symbol.id, toDisplayPayload(instrument, symbol))
      await upsertBinding(token, symbol.id, instrument)
      setPublishMessage(`${platformSymbol} 已${created ? '创建' : '补齐'}为平台品种，并已保存展示配置和数据源绑定`)
    } catch (err) {
      setPublishError(err instanceof Error ? err.message : '发布平台品种失败')
    } finally {
      setPublishingId('')
    }
  }

  function providerForInstrument(instrument: ProviderInstrumentRow, providerRows: DataProviderRow[]) {
    return providerRows.find((provider) => provider.id === instrument.providerId)
  }

  async function findPlatformSymbol(token: string, platformSymbol: string) {
    const page = await getSymbolsPage(token, 0, 1000)
    return page.items.find((symbol) => normalizedSymbol(symbol.symbol) === platformSymbol)
  }

  async function upsertBinding(token: string, symbolId: string, instrument: ProviderInstrumentRow) {
    const payload = toBindingPayload(instrument)
    const bindings = await getSymbolProviderBindings(token, symbolId)
    const existingBinding = findMatchingBinding(bindings, instrument)
    if (existingBinding) {
      await updateSymbolProviderBinding(token, symbolId, existingBinding.id, payload)
      return
    }
    await createSymbolProviderBinding(token, symbolId, payload)
  }
}

function derivePlatformSymbol(instrument: ProviderInstrumentRow) {
  const baseAsset = normalizeAsset(instrument.baseAsset)
  const quoteAsset = normalizeAsset(instrument.quoteAsset)
  if (baseAsset && quoteAsset) return `${baseAsset}${quoteAsset}`
  return normalizedSymbol(instrument.providerSymbol)
}

function normalizedSymbol(value: string) {
  return value.trim().toUpperCase().replace(/^[A-Z]+:/, '').replace(/[^A-Z0-9]/g, '')
}

function normalizeAsset(value: string | null) {
  return value?.trim().toUpperCase().replace(/[^A-Z0-9]/g, '') || ''
}

function toSymbolPayload(
  instrument: ProviderInstrumentRow,
  provider: DataProviderRow | undefined,
  platformSymbol: string
): SymbolPayload {
  const display = toDisplayPayload(instrument)
  const tradingConfig = defaultTradingConfig(instrument.assetClass)
  return {
    symbol: platformSymbol,
    displayName: instrument.displayName?.trim() || displayNameFor(instrument, platformSymbol),
    provider: provider?.code ?? '',
    providerSymbol: instrument.providerSymbol,
    assetClass: instrument.assetClass,
    productType: productTypeForAssetClass(instrument.assetClass),
    baseCurrency: normalizeAsset(instrument.baseAsset) || inferBaseCurrency(platformSymbol),
    quoteCurrency: normalizeAsset(instrument.quoteAsset) || inferQuoteCurrency(platformSymbol),
    ...tradingConfig,
    enabled: true,
    ...display
  }
}

function productTypeForAssetClass(assetClass: string): ProductType {
  const normalizedAssetClass = assetClass.trim().toUpperCase()
  if (normalizedAssetClass === 'CRYPTO' || normalizedAssetClass === 'SPOT') return 'CRYPTO_SPOT'
  if (normalizedAssetClass === 'INVERSE_PERP' || normalizedAssetClass === 'INVERSE_PERPETUAL') return 'INVERSE_PERP'
  if (['LINEAR_PERP', 'LINEAR_PERPETUAL', 'PERPETUAL', 'SWAP', 'FUTURES', 'CONTRACT'].includes(normalizedAssetClass)) {
    return 'LINEAR_PERP'
  }
  return 'FX_MARGIN'
}

function toDisplayPayload(instrument: ProviderInstrumentRow, symbol?: SymbolRow): SymbolDisplayPayload {
  return {
    iconUrl: symbol?.iconUrl ?? null,
    displayEnabled: symbol?.displayEnabled ?? true,
    quoteEnabled: symbol?.quoteEnabled ?? true,
    chartEnabled: symbol?.chartEnabled ?? true,
    orderBookEnabled: symbol?.orderBookEnabled ?? true,
    tradable: symbol?.tradable ?? true,
    featured: symbol?.featured ?? false,
    displayGroup: (symbol?.displayGroup ?? instrument.assetClass) || null,
    displayOrder: symbol?.displayOrder ?? 0
  }
}

function toBindingPayload(instrument: ProviderInstrumentRow): SymbolProviderBindingPayload {
  return {
    providerId: instrument.providerId,
    providerInstrumentId: instrument.id,
    providerSymbol: instrument.providerSymbol,
    priority: 100,
    enabled: true,
    configJson: '{}'
  }
}

function findMatchingBinding(bindings: SymbolProviderBindingRow[], instrument: ProviderInstrumentRow) {
  const providerSymbol = normalizedSymbol(instrument.providerSymbol)
  return bindings.find((binding) => {
    if (binding.providerInstrumentId === instrument.id) return true
    return binding.providerId === instrument.providerId && normalizedSymbol(binding.providerSymbol) === providerSymbol
  })
}

function displayNameFor(instrument: ProviderInstrumentRow, platformSymbol: string) {
  const baseAsset = normalizeAsset(instrument.baseAsset)
  const quoteAsset = normalizeAsset(instrument.quoteAsset)
  if (baseAsset && quoteAsset) return `${baseAsset} / ${quoteAsset}`
  return platformSymbol
}

function defaultTradingConfig(assetClass: string) {
  const normalizedAssetClass = assetClass.trim().toUpperCase()
  if (normalizedAssetClass === 'CRYPTO') {
    return {
      pipSize: '0.01',
      tickSize: '0.1',
      lotSize: '1',
      minLot: '0.0001',
      maxLot: '100',
      leverage: 20,
      spreadMarkup: '0'
    }
  }
  if (normalizedAssetClass === 'METAL' || normalizedAssetClass === 'METALS') {
    return {
      pipSize: '0.01',
      tickSize: '0.1',
      lotSize: '100',
      minLot: '0.01',
      maxLot: '100',
      leverage: 100,
      spreadMarkup: '0'
    }
  }
  return {
    pipSize: '0.0001',
    tickSize: '0.00001',
    lotSize: '100000',
    minLot: '0.01',
    maxLot: '100',
    leverage: 100,
    spreadMarkup: '0'
  }
}

function inferBaseCurrency(platformSymbol: string) {
  const quoteCurrency = inferQuoteCurrency(platformSymbol)
  return quoteCurrency && platformSymbol.length > quoteCurrency.length
    ? platformSymbol.slice(0, -quoteCurrency.length)
    : platformSymbol
}

function inferQuoteCurrency(platformSymbol: string) {
  const knownQuotes = ['USDT', 'USDC', 'USD', 'JPY', 'EUR', 'GBP', 'AUD', 'CAD', 'CHF', 'CNH', 'BTC', 'ETH']
  return knownQuotes.find((quote) => platformSymbol.endsWith(quote)) ?? 'USD'
}
