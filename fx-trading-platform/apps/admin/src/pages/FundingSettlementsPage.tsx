import type { FundingSettlement } from '@fx-platform/shared-types'

import { getAccountsPage } from '../services/adminApi'
import { getAccountFundingSettlements, loadAllAdminPages } from '../services/adminTradingApi'
import { DataTable, display, formatDateTime, PageHeader, StateBlock, useAdminData } from './adminPageUtils'

export function FundingSettlementsPage() {
  const { data, loading, error } = useAdminData(loadFundingSettlements)

  return (
    <>
      <PageHeader
        title="资金费结算"
        description="按账户并行加载所有 Demo 永续合约的真实结算记录。"
      />
      {loading ? <StateBlock tone="loading">正在加载资金费结算</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error ? (
        <DataTable
          rows={data ?? []}
          emptyText="暂无资金费结算"
          columns={[
            { title: '账户 ID', render: (row) => display(row.accountId) },
            { title: '品种', render: (row) => display(row.symbol) },
            { title: '持仓方向', render: (row) => display(row.positionSide) },
            { title: '保证金模式', render: (row) => display(row.marginMode) },
            { title: '资金费率', render: (row) => display(row.fundingRate) },
            { title: '结算金额', render: (row) => display(row.amount) },
            { title: '标记价格', render: (row) => display(row.markPrice) },
            { title: '实际来源', render: (row) => display(row.source) },
            { title: '结算时间', render: (row) => formatDateTime(row.fundingTime) }
          ]}
        />
      ) : null}
    </>
  )
}

async function loadFundingSettlements(token: string): Promise<FundingSettlement[]> {
  const accounts = await loadAllAdminPages((page, size) => getAccountsPage(token, page, size))
  const settlementsByAccount = await Promise.all(
    accounts
      .filter((account) => account.accountType === 'DEMO')
      .map((account) => getAccountFundingSettlements(account.id, token))
  )
  return settlementsByAccount
    .flat()
    .sort((left, right) => (right.fundingTime ?? '').localeCompare(left.fundingTime ?? ''))
}
