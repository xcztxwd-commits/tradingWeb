import { getPaymentMethodsPage } from '../services/adminApi'
import { AdminPageTable, display, useAdminData } from './adminPageUtils'

export function PaymentMethodsPage() {
  const { data, loading, error } = useAdminData(getPaymentMethodsPage)

  return (
    <AdminPageTable
      title="支付方式"
      description="查看后台支付方式配置，新增和编辑接口已由 Java 后台提供。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无支付方式"
      columns={[
        { title: '名称', render: (row) => row.name },
        { title: '类型', render: (row) => row.methodType },
        { title: '币种', render: (row) => row.currency },
        { title: '状态', render: (row) => display(row.enabled) },
        { title: '排序', render: (row) => display(row.displayOrder) }
      ]}
    />
  )
}
