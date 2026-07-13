import { useState } from 'react'
import { Link } from 'react-router-dom'

import { getAccountsPage } from '../services/adminApi'
import { loadAllAdminPages } from '../services/adminTradingApi'
import { DataTable, display, PageHeader, StateBlock, useAdminData } from './adminPageUtils'
import { filterAccounts, type AccountFilters } from './accountDiscovery'

const emptyFilters: AccountFilters = {
  accountId: '',
  userId: '',
  accountType: '',
  status: ''
}

export function AccountsPage() {
  const { data, loading, error } = useAdminData(loadAccounts)
  const [filters, setFilters] = useState(emptyFilters)
  const accounts = data ?? []
  const filteredAccounts = filterAccounts(accounts, filters)

  const updateFilter = (key: keyof AccountFilters, value: string) => {
    setFilters((current) => ({ ...current, [key]: value }))
  }

  return (
    <>
      <PageHeader title="账户管理" description="查看全局交易账户余额、权益和保证金状态。" />
      <section className="feature-filters" aria-label="账户筛选">
        <AccountFilter label="账户 ID" value={filters.accountId} onChange={(value) => updateFilter('accountId', value)} />
        <AccountFilter label="用户 ID" value={filters.userId} onChange={(value) => updateFilter('userId', value)} />
        <AccountFilter label="账户类型" value={filters.accountType} onChange={(value) => updateFilter('accountType', value)} />
        <AccountFilter label="状态" value={filters.status} onChange={(value) => updateFilter('status', value)} />
      </section>

      {loading ? <StateBlock tone="loading">正在加载全部账户</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error ? (
        <>
          <div className="page-meta" role="status">显示 {filteredAccounts.length} / {accounts.length} 个账户</div>
          <DataTable
            rows={filteredAccounts}
            emptyText={accounts.length === 0 ? '暂无账户数据' : '没有符合当前筛选条件的账户'}
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
        </>
      ) : null}
    </>
  )
}

async function loadAccounts(token: string) {
  return loadAllAdminPages((page, size) => getAccountsPage(token, page, size))
}

function AccountFilter({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="feature-field">
      <span>{label}</span>
      <input type="search" value={value} onChange={(event) => onChange(event.target.value)} />
    </label>
  )
}
