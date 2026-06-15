import { getRiskConfigs } from '../services/adminApi'
import { DataTable, display, formatDateTime, PageHeader, StateBlock, useAdminData } from './adminPageUtils'

export function RiskPage() {
  const { data, loading, error } = useAdminData(getRiskConfigs)

  return (
    <>
      <PageHeader title="风控配置" description="查看 PostgreSQL risk.risk_configs 表中的后台风控参数。" />
      {loading ? <StateBlock>正在加载风控配置</StateBlock> : null}
      {error ? <StateBlock>{error}</StateBlock> : null}
      {!loading && !error ? (
        <DataTable
          rows={data ?? []}
          emptyText="暂无风控配置"
          columns={[
            { title: '品种', render: (row) => row.symbol },
            { title: '最大杠杆', render: (row) => display(row.maxLeverage) },
            { title: '最大手数', render: (row) => display(row.maxLots) },
            { title: '追加保证金线', render: (row) => display(row.marginCallLevel) },
            { title: '强平线', render: (row) => display(row.stopOutLevel) },
            { title: '状态', render: (row) => display(row.enabled) },
            { title: '更新时间', render: (row) => formatDateTime(row.updatedAt) }
          ]}
        />
      ) : null}
    </>
  )
}
