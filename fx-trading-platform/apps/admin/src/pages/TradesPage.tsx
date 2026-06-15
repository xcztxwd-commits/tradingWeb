import { getTradesPage } from '../services/adminApi'
import { AdminPageTable, display, formatDateTime, useAdminData } from './adminPageUtils'

export function TradesPage() {
  const { data, loading, error } = useAdminData(getTradesPage)

  return (
    <AdminPageTable
      title="成交记录"
      description="查看由 MyBatis-Plus 后端接口返回的全部成交记录。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无成交记录"
      columns={[
        { title: '成交 ID', render: (row) => row.id },
        { title: '订单 ID', render: (row) => row.orderId },
        { title: '账户 ID', render: (row) => row.accountId },
        { title: '品种', render: (row) => row.symbol },
        { title: '方向', render: (row) => row.side },
        { title: '手数', render: (row) => display(row.lots) },
        { title: '成交价', render: (row) => display(row.price) },
        { title: '已实现盈亏', render: (row) => display(row.realizedPnl) },
        { title: '成交时间', render: (row) => formatDateTime(row.executedAt) }
      ]}
    />
  )
}
