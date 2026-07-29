import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import {
  createFundOrder,
  filterByStatus,
  getFundOrders,
  toNumber,
  useWalletController,
  type FundOrder
} from '@fx-platform/frontend-core'

import { formatApiError, type ApiErrorView } from '../../shared-widgets/data/userPageModels'
import { translateCoreMessage } from '../shared/translateCoreMessage'
import { createPendingOperationGuard } from '../shared/pendingOperationGuard'
import { getAssetRows, getUniqueValues, matchesWalletDateRange } from './walletRouteModel'
import type { FundOrderForm } from './walletRoute.types'

const fundOrderStatuses = ['ALL', 'PENDING_REVIEW', 'PENDING', 'APPROVED', 'REJECTED']
const fundOrderTypes = ['ALL', 'RECHARGE', 'WITHDRAWAL']

export function useWalletRouteController() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const wallet = useWalletController()
  const operationGuard = useRef(createPendingOperationGuard()).current
  const [fundOrders, setFundOrders] = useState<FundOrder[]>([])
  const [fundOrdersLoading, setFundOrdersLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [typeFilter, setTypeFilter] = useState('ALL')
  const [ledgerTypeFilter, setLedgerTypeFilter] = useState('ALL')
  const [ledgerWalletTypeFilter, setLedgerWalletTypeFilter] = useState('ALL')
  const [ledgerCurrencyFilter, setLedgerCurrencyFilter] = useState('ALL')
  const [ledgerFromDate, setLedgerFromDate] = useState('')
  const [ledgerToDate, setLedgerToDate] = useState('')
  const [selectedAsset, setSelectedAsset] = useState<string | null>(null)
  const [fundOrderForm, setFundOrderForm] = useState<FundOrderForm>({
    orderType: 'RECHARGE',
    amount: '',
    currency: '',
    note: ''
  })
  const [transferOpen, setTransferOpen] = useState(false)
  const [resetOpen, setResetOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [apiError, setApiError] = useState<ApiErrorView | null>(null)

  const currency = wallet.account?.baseCurrency ?? 'USDT'
  const pendingWithdrawalAmount = useMemo(() => fundOrders
    .filter((order) => order.orderType === 'WITHDRAWAL')
    .filter((order) => order.status === 'PENDING_REVIEW' || order.status === 'PENDING')
    .reduce((total, order) => total + (toNumber(order.amount) ?? 0), 0), [fundOrders])
  const frozenAmount = (toNumber(wallet.account?.usedMargin) ?? 0) + pendingWithdrawalAmount
  const spotAvailable = wallet.walletBalances.find((balance) => balance.walletType === 'SPOT' && balance.asset === 'USDT')?.available ?? 0
  const perpAvailable = wallet.account?.freeMargin ?? 0
  const transferAvailable = wallet.transferDirection === 'SPOT_TO_PERP' ? spotAvailable : perpAvailable
  const visibleFundOrders = useMemo(
    () => filterByStatus(fundOrders, statusFilter).filter((order) => typeFilter === 'ALL' || order.orderType === typeFilter),
    [fundOrders, statusFilter, typeFilter]
  )
  const assetRows = useMemo(
    () => getAssetRows(wallet.account, wallet.walletBalances, wallet.assetLedgerEntries, frozenAmount),
    [frozenAmount, wallet.account, wallet.assetLedgerEntries, wallet.walletBalances]
  )
  const selectedAssetRow = useMemo(
    () => assetRows.find((asset) => asset.key === selectedAsset) ?? assetRows[0],
    [assetRows, selectedAsset]
  )
  const ledgerTypeOptions = useMemo(() => getUniqueValues(wallet.ledgerEntries.map((entry) => entry.entryType)), [wallet.ledgerEntries])
  const ledgerWalletTypeOptions = useMemo(() => getUniqueValues(wallet.ledgerEntries.map((entry) => entry.walletType).filter((value): value is string => Boolean(value))), [wallet.ledgerEntries])
  const ledgerCurrencyOptions = useMemo(() => getUniqueValues(wallet.ledgerEntries.map((entry) => entry.currency)), [wallet.ledgerEntries])
  const visibleLedgerEntries = useMemo(() => wallet.ledgerEntries
    .filter((entry) => ledgerTypeFilter === 'ALL' || entry.entryType === ledgerTypeFilter)
    .filter((entry) => ledgerWalletTypeFilter === 'ALL' || entry.walletType === ledgerWalletTypeFilter)
    .filter((entry) => ledgerCurrencyFilter === 'ALL' || entry.currency === ledgerCurrencyFilter)
    .filter((entry) => matchesWalletDateRange(entry.createdAt, ledgerFromDate, ledgerToDate)), [
      ledgerCurrencyFilter,
      ledgerFromDate,
      ledgerToDate,
      ledgerTypeFilter,
      ledgerWalletTypeFilter,
      wallet.ledgerEntries
    ])

  const loadFundOrders = useCallback(async () => {
    if (!wallet.token || !wallet.accountId) return
    setFundOrdersLoading(true)
    setApiError(null)
    try {
      setFundOrders(await getFundOrders(wallet.accountId, wallet.token))
    } catch (error) {
      setApiError(formatApiError(error))
    } finally {
      setFundOrdersLoading(false)
    }
  }, [wallet.accountId, wallet.token])

  useEffect(() => { void loadFundOrders() }, [loadFundOrders])

  const submitFundOrder = () => operationGuard.run('wallet:fund-order', async () => {
    if (!wallet.token || !wallet.accountId) return
    setNotice(null)
    setApiError(null)
    try {
      const created = await createFundOrder({
        accountId: wallet.accountId,
        orderType: fundOrderForm.orderType,
        amount: fundOrderForm.amount,
        currency: fundOrderForm.currency || currency,
        note: fundOrderForm.note
      }, wallet.token)
      setFundOrders((current) => [created, ...current.filter((order) => order.id !== created.id)])
      setFundOrderForm((current) => ({ ...current, orderType: 'RECHARGE', amount: '', note: '' }))
      setNotice(t('assets.requestSubmitted'))
      await loadFundOrders()
    } catch (error) {
      setApiError(formatApiError(error))
    }
  })

  const submitTransfer = () => operationGuard.run('wallet:transfer', async () => {
    try {
      const transfer = await wallet.submitTransfer(transferAvailable)
      if (transfer) setTransferOpen(false)
    } catch {
      // The core controller preserves the API error descriptor.
    }
  })
  const submitReset = () => operationGuard.run('wallet:reset', async () => {
    try {
      const result = await wallet.submitReset()
      if (result) setResetOpen(false)
    } catch {
      // The core controller preserves the API error descriptor.
    }
  })

  return {
    token: wallet.token,
    accountId: wallet.accountId,
    account: wallet.account,
    sessionMode: wallet.sessionMode,
    sessionError: translateCoreMessage(wallet.sessionError, t),
    loginRequired: wallet.loginRequired,
    assetRows,
    selectedAssetRow,
    visibleFundOrders,
    visibleLedgerEntries,
    fundOrdersLoading,
    statusFilter,
    typeFilter,
    ledgerTypeFilter,
    ledgerWalletTypeFilter,
    ledgerCurrencyFilter,
    ledgerFromDate,
    ledgerToDate,
    ledgerTypeOptions,
    ledgerWalletTypeOptions,
    ledgerCurrencyOptions,
    fundOrderStatuses,
    fundOrderTypes,
    fundOrderForm,
    currency,
    frozenAmount,
    pendingWithdrawalAmount,
    transferOpen,
    resetOpen,
    transferDirection: wallet.transferDirection,
    transferAmount: wallet.transferAmount,
    transferRequestId: wallet.transferRequestId,
    transferPending: wallet.transferPending,
    transferError: translateCoreMessage(wallet.transferError, t),
    transferAvailable,
    resetRequestId: wallet.resetRequestId,
    resetPending: wallet.resetPending,
    resetError: translateCoreMessage(wallet.resetError, t),
    notice: notice ?? translateCoreMessage(wallet.notice, t),
    apiError,
    setStatusFilter,
    setTypeFilter,
    setLedgerTypeFilter,
    setLedgerWalletTypeFilter,
    setLedgerCurrencyFilter,
    setLedgerFromDate,
    setLedgerToDate,
    setSelectedAsset,
    setFundOrderField: <K extends keyof FundOrderForm>(field: K, value: FundOrderForm[K]) => setFundOrderForm((current) => ({ ...current, [field]: value })),
    clearLedgerFilters() {
      setLedgerTypeFilter('ALL')
      setLedgerWalletTypeFilter('ALL')
      setLedgerCurrencyFilter('ALL')
      setLedgerFromDate('')
      setLedgerToDate('')
    },
    refresh: () => operationGuard.run('wallet:refresh', async () => { await Promise.all([wallet.retrySession(), loadFundOrders()]) }),
    retryFundOrders: loadFundOrders,
    submitFundOrder,
    openLogin: () => navigate('/login?redirect=/wallet'),
    openTransfer() {
      setNotice(null)
      wallet.beginTransfer()
      setTransferOpen(true)
    },
    closeTransfer: () => setTransferOpen(false),
    changeTransferDirection: wallet.changeTransferDirection,
    changeTransferAmount: wallet.changeTransferAmount,
    submitTransfer,
    openReset() {
      setNotice(null)
      wallet.beginReset()
      setResetOpen(true)
    },
    closeReset: () => setResetOpen(false),
    submitReset
  }
}
