import { getSymbolsPage } from '../services/adminApi'
import { AdminPageTable, display, useAdminData } from './adminPageUtils'

export function SymbolsPage() {
  const { data, loading, error } = useAdminData(getSymbolsPage)

  return (
    <AdminPageTable
      title="品种配置"
      description="查看交易品种配置；完整新增和编辑品种可作为下一阶段补齐。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无品种数据"
      columns={[
        { title: '品种', render: (row) => row.symbol },
        { title: '名称', render: (row) => row.displayName },
        { title: '资产类别', render: (row) => row.assetClass },
        { title: '基础币种', render: (row) => row.baseCurrency },
        { title: '报价币种', render: (row) => row.quoteCurrency },
        { title: '杠杆', render: (row) => display(row.leverage) },
        { title: '状态', render: (row) => display(row.enabled) }
      ]}
    />
  )
}
