import { useEffect, useMemo, useState } from 'react'

import {
  createAccountDataController,
  defaultAccountDataControllerDependencies,
  type AccountDataControllerDependencies
} from './accountOperations.ts'
import type { AccountSessionData } from './accountSessionModels.ts'

type UseAccountDataOptions = {
  enabled?: boolean
  refreshMs?: number
  dependencies?: AccountDataControllerDependencies
}

const emptyData: Omit<AccountSessionData, 'account'> = {
  orders: [],
  trades: [],
  positions: [],
  positionHistory: [],
  fundingSettlements: [],
  transfers: [],
  ledgerEntries: [],
  assetLedgerEntries: [],
  walletBalances: []
}

export function useAccountData({
  enabled = true,
  refreshMs = 15_000,
  dependencies = defaultAccountDataControllerDependencies
}: UseAccountDataOptions = {}) {
  const controller = useMemo(
    () => createAccountDataController(dependencies, { refreshMs }),
    [dependencies, enabled, refreshMs]
  )
  const [snapshot, setSnapshot] = useState(controller.getSnapshot)

  useEffect(() => {
    if (!enabled) return
    const unsubscribe = controller.subscribe(setSnapshot)
    void controller.start()
    return () => {
      unsubscribe()
      controller.dispose()
    }
  }, [controller, enabled])

  const data = snapshot.data
  return {
    state: snapshot.state,
    token: snapshot.token,
    account: data?.account,
    accountId: data?.account.id,
    orders: data?.orders ?? emptyData.orders,
    trades: data?.trades ?? emptyData.trades,
    positions: data?.positions ?? emptyData.positions,
    positionHistory: data?.positionHistory ?? emptyData.positionHistory,
    fundingSettlements: data?.fundingSettlements ?? emptyData.fundingSettlements,
    transfers: data?.transfers ?? emptyData.transfers,
    ledgerEntries: data?.ledgerEntries ?? emptyData.ledgerEntries,
    assetLedgerEntries: data?.assetLedgerEntries ?? emptyData.assetLedgerEntries,
    walletBalances: data?.walletBalances ?? emptyData.walletBalances,
    sessionReady: snapshot.state.status === 'ready',
    sessionMode: snapshot.state.status,
    sessionError: snapshot.state.status === 'error' ? snapshot.state.message : null,
    sessionApiError: snapshot.state.status === 'error' ? snapshot.state.error : null,
    sessionAuthStatus: snapshot.authStatus,
    loginRequired: snapshot.state.status === 'login-required',
    refreshAccountData: controller.refresh,
    retrySession: controller.retry
  }
}

export type AccountDataControllerResult = ReturnType<typeof useAccountData>
