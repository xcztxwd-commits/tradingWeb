import { Link } from 'react-router-dom'

import { getMarketStatus } from '../services/adminApi'
import { display, PageHeader, StateBlock, useAdminData } from './adminPageUtils'

export function MarketStatusPage() {
  const { data, loading, error } = useAdminData(getMarketStatus)

  return (
    <>
      <PageHeader title="行情状态" description="查看后台行情源、Redis 缓存和报价新鲜度状态。" />
      <p className="operations-page-link">
        <Link className="table-action" to="/market/funding-config">查看每个 Perpetual 品种的资金费来源与回退状态</Link>
      </p>
      {loading ? <StateBlock>正在加载行情状态</StateBlock> : null}
      {error ? <StateBlock>{error}</StateBlock> : null}
      {!loading && !error ? (
        <section className="detail-grid">
          <div>
            <span>行情源</span>
            <strong>{display(data?.status)}</strong>
          </div>
          <div>
            <span>Massive 配置</span>
            <strong>{display(data?.massiveConfigured)}</strong>
          </div>
          <div>
            <span>Redis 缓存</span>
            <strong>{display(data?.redisCacheEnabled)}</strong>
          </div>
          <div>
            <span>报价过期阈值</span>
            <strong>{display(data?.quoteStaleMs)} ms</strong>
          </div>
          <div>
            <span>来源模式</span>
            <strong>{display(data?.sourceMode)}</strong>
          </div>
          <div>
            <span>Provider 状态</span>
            <strong>{display(data?.providerStatus)}</strong>
          </div>
        </section>
      ) : null}
    </>
  )
}
