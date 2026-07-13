import { Link } from 'react-router-dom'

import { getAccountsPage } from '../services/adminApi'
import { AdminPageTable, display, useAdminData } from './adminPageUtils'

export function AccountsPage() {
  const { data, loading, error } = useAdminData(getAccountsPage)

  return (
    <AdminPageTable
      title="账户管理"
      description="查看全局交易账户余额、权益和保证金状态。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无账户数据"
      columns={[
        {
          title: '账户 ID',
          render: (row) => (
            <Link className="table-action" to={`/accounts/${encodeURIComponent(row.id)}`}>
              {row.id}
            </Link>
          )
        },
        { title: '用户 ID', render: (row) => row.userId },
        { title: '类型', render: (row) => display(row.accountType) },
        { title: '余额', render: (row) => display(row.balance) },
        { title: '权益', render: (row) => display(row.equity) },
        { title: '可用保证金', render: (row) => display(row.freeMargin) },
        { title: '状态', render: (row) => display(row.status) }
      ]}
    />
  )
}
