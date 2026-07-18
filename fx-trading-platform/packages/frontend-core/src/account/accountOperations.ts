import type { AccountTransferDirection, AccountTransferRequest, DemoResetRequest } from '@fx-platform/shared-types'
import {
  createDemoAccount,
  getAccounts,
  getAccountSummary,
  getAccountTransfers,
  getAssetLedger,
  getFundingSettlements,
  getLedgerEntries,
  getOrders,
  getPositionHistory,
  getPositions,
  getSessionStatus,
  getTrades,
  getWalletBalances,
  resetDemoAccount,
  transferDemoFunds,
  type SessionAuthStatus
} from '../api/index.ts'
import { clearStoredAuthToken, readStoredAuthToken } from '../auth/sessionStorage.ts'
import { subscribeTradingSessionEvents } from '../market/marketStream.ts'
import type { AccountSummary } from '../models/index.ts'
import {
  type AccountDataState,
  type CoreMessage,
  isAuthSessionFailure,
  toAccountApiError,
  toAccountErrorMessage
} from './accountErrors.ts'
import {
  type AccountSessionData,
  createEmptyAccountSessionData
} from './accountSessionModels.ts'
import {
  createAccountRefreshCoordinator,
  createLatestSingleFlightRefreshGate,
  type AccountRefreshCoordinator,
  type RefreshScheduler,
  type RefreshVisibilitySource
} from './accountRefreshCoordinator.ts'

export type FirstAccountDependencies = {
  getAccounts: typeof getAccounts
  createDemoAccount: typeof createDemoAccount
}

export type AccountSnapshotDependencies = {
  getAccountSummary: typeof getAccountSummary
  getOrders: typeof getOrders
  getTrades: typeof getTrades
  getPositions: typeof getPositions
  getPositionHistory: typeof getPositionHistory
  getFundingSettlements: typeof getFundingSettlements
  getAccountTransfers: typeof getAccountTransfers
  getLedgerEntries: typeof getLedgerEntries
  getAssetLedger: typeof getAssetLedger
  getWalletBalances: typeof getWalletBalances
}

export type AccountDataControllerDependencies = FirstAccountDependencies & {
  readStoredAuthToken: typeof readStoredAuthToken
  clearStoredAuthToken: typeof clearStoredAuthToken
  getSessionStatus: typeof getSessionStatus
  loadAccountData(token: string, accountId: string): Promise<AccountSessionData>
  subscribeAccountEvents(
    token: string,
    onEvent: () => void,
    onReconnect: () => void
  ): () => void
}

export type AccountControllerSnapshot = {
  token: string | null
  authStatus: SessionAuthStatus
  state: AccountDataState<AccountSessionData>
  data?: AccountSessionData
}

export type AccountDataController = {
  getSnapshot(): AccountControllerSnapshot
  subscribe(listener: (snapshot: AccountControllerSnapshot) => void): () => void
  start(): Promise<void>
  retry(): Promise<void>
  refresh(): Promise<void>
  dispose(): void
}

type AccountDataControllerOptions = {
  refreshMs?: number
  coalesceMs?: number
  scheduler?: RefreshScheduler
  visibility?: RefreshVisibilitySource
}

const defaultFirstAccountDependencies: FirstAccountDependencies = { getAccounts, createDemoAccount }
const defaultSnapshotDependencies: AccountSnapshotDependencies = {
  getAccountSummary,
  getOrders,
  getTrades,
  getPositions,
  getPositionHistory,
  getFundingSettlements,
  getAccountTransfers,
  getLedgerEntries,
  getAssetLedger,
  getWalletBalances
}

export const defaultAccountDataControllerDependencies: AccountDataControllerDependencies = {
  readStoredAuthToken,
  clearStoredAuthToken,
  getSessionStatus,
  ...defaultFirstAccountDependencies,
  loadAccountData: (token, accountId) => loadAccountData(token, accountId),
  subscribeAccountEvents: subscribeTradingSessionEvents
}

export function selectActiveDemoAccount(accounts: AccountSummary[]) {
  return accounts.find((account) =>
    account.accountType.trim().toUpperCase() === 'DEMO'
    && account.status.trim().toUpperCase() === 'ACTIVE'
  )
}

export async function firstOrCreatedAccount(
  token: string,
  dependencies: FirstAccountDependencies = defaultFirstAccountDependencies
) {
  const accounts = await dependencies.getAccounts(token)
  return selectActiveDemoAccount(accounts) ?? dependencies.createDemoAccount(token)
}

export async function loadAccountData(
  token: string,
  accountId: string,
  dependencies: AccountSnapshotDependencies = defaultSnapshotDependencies
): Promise<AccountSessionData> {
  const [
    account,
    orders,
    trades,
    positions,
    positionHistory,
    fundingSettlements,
    transfers,
    ledgerEntries,
    assetLedgerEntries,
    walletBalances
  ] = await Promise.all([
    dependencies.getAccountSummary(accountId, token),
    dependencies.getOrders(accountId, token),
    dependencies.getTrades(accountId, token),
    dependencies.getPositions(accountId, token),
    dependencies.getPositionHistory(accountId, token),
    dependencies.getFundingSettlements(accountId, token),
    dependencies.getAccountTransfers(accountId, token),
    dependencies.getLedgerEntries(accountId, token),
    dependencies.getAssetLedger(accountId, token),
    dependencies.getWalletBalances(accountId, token)
  ])
  return {
    account,
    orders,
    trades,
    positions,
    positionHistory,
    fundingSettlements,
    transfers,
    ledgerEntries,
    assetLedgerEntries,
    walletBalances
  }
}

export function createAccountDataController(
  dependencies: AccountDataControllerDependencies = defaultAccountDataControllerDependencies,
  {
    refreshMs = 15_000,
    coalesceMs = 200,
    scheduler,
    visibility
  }: AccountDataControllerOptions = {}
): AccountDataController {
  let snapshot: AccountControllerSnapshot = {
    token: null,
    authStatus: 'guest',
    state: { status: 'loading' }
  }
  let disposed = false
  let generation = 0
  let coordinator: AccountRefreshCoordinator | undefined
  let unsubscribeEvents: (() => void) | undefined
  const listeners = new Set<(snapshot: AccountControllerSnapshot) => void>()
  const refreshGate = createLatestSingleFlightRefreshGate<AccountSessionData>()

  const emit = (next: AccountControllerSnapshot) => {
    if (disposed) return
    snapshot = next
    for (const listener of listeners) listener(snapshot)
  }

  const stopRuntime = () => {
    unsubscribeEvents?.()
    unsubscribeEvents = undefined
    coordinator?.dispose()
    coordinator = undefined
  }

  const requireLogin = (authStatus: SessionAuthStatus = 'guest') => {
    dependencies.clearStoredAuthToken()
    stopRuntime()
    refreshGate.invalidate()
    emit({ token: null, authStatus, state: { status: 'login-required' } })
  }

  const markError = (error: unknown) => {
    if (isAuthSessionFailure(error)) {
      requireLogin('invalid_token')
      return
    }
    const apiError = toAccountApiError(error)
    emit({
      ...snapshot,
      state: { status: 'error', error: apiError, message: toAccountErrorMessage(apiError) }
    })
  }

  const refresh = async () => {
    const { token, data } = snapshot
    if (!token || !data?.account.id) return
    try {
      await refreshGate.request(
        () => dependencies.loadAccountData(token, data.account.id),
        (nextData) => emit({
          token,
          authStatus: snapshot.authStatus,
          data: nextData,
          state: { status: 'ready', data: nextData }
        })
      )
    } catch (error) {
      markError(error)
      throw error
    }
  }

  const startRuntime = (token: string) => {
    stopRuntime()
    coordinator = createAccountRefreshCoordinator({
      refresh,
      pollMs: refreshMs,
      coalesceMs,
      ...(scheduler ? { scheduler } : {}),
      ...(visibility ? { visibility } : {}),
      onError: markError
    })
    coordinator.start()
    unsubscribeEvents = dependencies.subscribeAccountEvents(
      token,
      () => coordinator?.notifyEvent(),
      () => coordinator?.notifyReconnect()
    )
  }

  const boot = async () => {
    const bootGeneration = ++generation
    stopRuntime()
    refreshGate.invalidate()
    emit({ ...snapshot, state: { status: 'loading', ...(snapshot.data ? { data: snapshot.data } : {}) } })
    try {
      const savedToken = dependencies.readStoredAuthToken()
      const status = await dependencies.getSessionStatus(savedToken)
      if (disposed || bootGeneration !== generation) return
      if (status.status === 'invalid_token') {
        requireLogin('invalid_token')
        return
      }
      if (!savedToken || status.status === 'guest' || !status.authenticated) {
        requireLogin('guest')
        return
      }

      const account = await firstOrCreatedAccount(savedToken, dependencies)
      if (disposed || bootGeneration !== generation) return
      const previousData = snapshot.data?.account.id === account.id
        ? snapshot.data
        : createEmptyAccountSessionData(account)
      emit({
        token: savedToken,
        authStatus: 'valid_token',
        data: previousData,
        state: { status: 'loading', data: previousData }
      })
      await refresh()
      if (disposed || bootGeneration !== generation || snapshot.state.status !== 'ready') return
      startRuntime(savedToken)
    } catch (error) {
      if (!disposed && bootGeneration === generation) markError(error)
    }
  }

  return {
    getSnapshot: () => snapshot,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    start: boot,
    retry: boot,
    refresh,
    dispose() {
      if (disposed) return
      disposed = true
      generation += 1
      stopRuntime()
      refreshGate.invalidate()
      listeners.clear()
    }
  }
}

export function validateWalletTransfer(amount: string, available: string | number): CoreMessage | null {
  const numericAmount = Number(amount)
  const numericAvailable = Number(available)
  if (!Number.isFinite(numericAmount) || numericAmount <= 0) return { key: 'assets.transferAmountInvalid' }
  if (!Number.isFinite(numericAvailable) || numericAmount > numericAvailable) {
    return { key: 'assets.transferBalanceInsufficient' }
  }
  return null
}

type WalletTransferInput = {
  accountId: string
  token: string
  direction: AccountTransferDirection
  amount: string
  requestId: string
}

type WalletTransferDependencies = {
  transferDemoFunds: typeof transferDemoFunds
  refresh(): Promise<unknown>
}

export async function runWalletTransfer(
  input: WalletTransferInput,
  dependencies: WalletTransferDependencies
) {
  const payload: AccountTransferRequest = {
    direction: input.direction,
    amount: Number(input.amount),
    requestId: input.requestId
  }
  const response = await dependencies.transferDemoFunds(input.accountId, payload, input.token)
  await dependencies.refresh()
  return response
}

type WalletResetInput = {
  accountId: string
  token: string
  requestId: string
}

type WalletResetDependencies = {
  resetDemoAccount: typeof resetDemoAccount
  refresh(): Promise<unknown>
}

export async function runWalletReset(input: WalletResetInput, dependencies: WalletResetDependencies) {
  const payload: DemoResetRequest = { requestId: input.requestId }
  const response = await dependencies.resetDemoAccount(input.accountId, payload, input.token)
  await dependencies.refresh()
  return response
}

export const defaultWalletMutationDependencies = { transferDemoFunds, resetDemoAccount }
