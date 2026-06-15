import { getLedgerPage } from '../services/adminApi'
import { AdminPageTable, display, formatDateTime, useAdminData } from './adminPageUtils'

export function LedgerPage() {
  const { data, loading, error } = useAdminData(getLedgerPage)

  return (
    <AdminPageTable
      title="资金流水"
      description="查看账户资金流水，资金写操作继续通过 Java LedgerService 记录。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无资金流水"
      columns={[
        { title: '流水 ID', render: (row) => row.id },
        { title: '账户 ID', render: (row) => row.accountId },
        { title: '类型', render: (row) => row.entryType },
        { title: '金额', render: (row) => display(row.amount) },
        { title: '余额', render: (row) => display(row.balanceAfter) },
        { title: '币种', render: (row) => row.currency },
        { title: '时间', render: (row) => formatDateTime(row.createdAt) }
      ]}
    />
  )
}
