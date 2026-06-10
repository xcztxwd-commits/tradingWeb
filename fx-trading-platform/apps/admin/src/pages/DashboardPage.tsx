import { Activity, ClipboardList, Database, ShieldCheck, Users, Wallet } from 'lucide-react'

import { getDashboardSummary } from '../services/adminApi'
import { PageHeader, StateBlock, useAdminData } from './adminPageUtils'

const statItems = [
  { key: 'userCount', label: '用户总数', icon: Users },
  { key: 'accountCount', label: '账户总数', icon: Wallet },
  { key: 'orderCount', label: '订单总数', icon: ClipboardList },
  { key: 'openPositionCount', label: '打开持仓', icon: Activity },
  { key: 'symbolCount', label: '交易品种', icon: Database },
  { key: 'auditLogCount', label: '审计事件', icon: ShieldCheck }
] as const

export function DashboardPage() {
  const { data, loading, error } = useAdminData(getDashboardSummary)

  return (
    <>
      <PageHeader title="控制台" description="后台核心运营指标，数据来自 Java 后台 dashboard summary 接口。" />
      {loading ? <StateBlock tone="loading">正在加载控制台数据</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error ? (
        <>
          <section className="dashboard-status-strip" aria-label="后台运行状态">
            <span>实时概览</span>
            <strong>{data?.openPositionCount ?? 0} 个持仓正在跟踪</strong>
          </section>
          <section className="stat-grid">
            {statItems.map((item) => (
              <div className="stat" key={item.key}>
                <span className="stat-icon">
                  <item.icon size={18} />
                </span>
                <span>{item.label}</span>
                <strong>{data?.[item.key] ?? 0}</strong>
              </div>
            ))}
          </section>
        </>
      ) : null}
    </>
  )
}
