import { getPositionsPage } from '../services/adminApi'
import { AdminPageTable, display, formatDateTime, useAdminData } from './adminPageUtils'

export function PositionsPage() {
  const { data, loading, error } = useAdminData(getPositionsPage)

  return (
    <AdminPageTable
      title="持仓管理"
      description="查看全部持仓；强平接口保留在 Java 后台交易服务边界内。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无持仓数据"
      columns={[
        { title: '持仓 ID', render: (row) => row.id },
        { title: '账户 ID', render: (row) => row.accountId },
        { title: '品种', render: (row) => row.symbol },
        { title: '方向', render: (row) => row.side },
        { title: '手数', render: (row) => display(row.lots) },
        { title: '浮动盈亏', render: (row) => display(row.floatingPnl) },
        { title: '状态', render: (row) => row.status },
        { title: '开仓时间', render: (row) => formatDateTime(row.openedAt) }
      ]}
    />
  )
}
