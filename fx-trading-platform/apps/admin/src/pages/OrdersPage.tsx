import { getOrdersPage } from '../services/adminApi'
import { AdminPageTable, display, formatDateTime, useAdminData } from './adminPageUtils'

export function OrdersPage() {
  const { data, loading, error } = useAdminData(getOrdersPage)

  return (
    <AdminPageTable
      title="订单管理"
      description="查看全部订单；撤单等写操作继续走现有 Java 后台接口。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无订单数据"
      columns={[
        { title: '订单 ID', render: (row) => row.id },
        { title: '用户 ID', render: (row) => row.userId },
        { title: '品种', render: (row) => row.symbol },
        { title: '方向', render: (row) => row.side },
        { title: '类型', render: (row) => row.orderType },
        { title: '状态', render: (row) => row.status },
        { title: '手数', render: (row) => display(row.lots) },
        { title: '创建时间', render: (row) => formatDateTime(row.createdAt) }
      ]}
    />
  )
}
