import { useCallback, useRef, useState } from 'react'
import type { AccountTransferDirection } from '@fx-platform/shared-types'

import type { CoreMessage } from '../coreMessage.ts'
import { toAccountApiError } from './accountErrors.ts'
import {
  defaultWalletMutationDependencies,
  runWalletReset,
  runWalletTransfer,
  validateWalletTransfer
} from './accountOperations.ts'
import { useAccountData } from './useAccountData.ts'

export function useWalletController() {
  const accountData = useAccountData()
  const [transferDirection, setTransferDirection] = useState<AccountTransferDirection>('SPOT_TO_PERP')
  const [transferAmount, setTransferAmount] = useState('')
  const [transferRequestId, setTransferRequestId] = useState('')
  const [transferPending, setTransferPending] = useState(false)
  const [transferError, setTransferError] = useState<CoreMessage | null>(null)
  const [resetRequestId, setResetRequestId] = useState('')
  const [resetPending, setResetPending] = useState(false)
  const [resetError, setResetError] = useState<CoreMessage | null>(null)
  const [notice, setNotice] = useState<CoreMessage | null>(null)
  const transferInFlight = useRef(false)
  const resetInFlight = useRef(false)

  const beginTransfer = useCallback(() => {
    setTransferAmount('')
    setTransferRequestId(globalThis.crypto.randomUUID())
    setTransferError(null)
    setNotice(null)
  }, [])

  const changeTransferDirection = useCallback((direction: AccountTransferDirection) => {
    setTransferDirection(direction)
    setTransferAmount('')
    setTransferRequestId(globalThis.crypto.randomUUID())
    setTransferError(null)
  }, [])

  const changeTransferAmount = useCallback((amount: string) => {
    setTransferAmount(amount)
    setTransferRequestId(globalThis.crypto.randomUUID())
    setTransferError(null)
  }, [])

  const submitTransfer = useCallback(async (available: string | number) => {
    const validation = validateWalletTransfer(transferAmount, available)
    if (validation) {
      setTransferError(validation)
      return undefined
    }
    if (!accountData.token || !accountData.accountId || transferInFlight.current) return undefined
    transferInFlight.current = true
    setTransferPending(true)
    setTransferError(null)
    setNotice(null)
    try {
      const response = await runWalletTransfer(
        {
          token: accountData.token,
          accountId: accountData.accountId,
          direction: transferDirection,
          amount: transferAmount,
          requestId: transferRequestId
        },
        {
          transferDemoFunds: defaultWalletMutationDependencies.transferDemoFunds,
          refresh: accountData.refreshAccountData
        }
      )
      setNotice({
        key: 'assets.transferCompleted',
        values: { amount: String(response.amount ?? transferAmount), direction: response.direction ?? transferDirection }
      })
      return response
    } catch (error) {
      const apiError = toAccountApiError(error)
      setTransferError({ key: 'assets.transferFailed', values: { message: apiError.message, code: apiError.code } })
      throw error
    } finally {
      transferInFlight.current = false
      setTransferPending(false)
    }
  }, [accountData.accountId, accountData.refreshAccountData, accountData.token, transferAmount, transferDirection, transferRequestId])

  const beginReset = useCallback(() => {
    setResetRequestId(globalThis.crypto.randomUUID())
    setResetError(null)
    setNotice(null)
  }, [])

  const submitReset = useCallback(async () => {
    if (!accountData.token || !accountData.accountId || resetInFlight.current) return undefined
    resetInFlight.current = true
    setResetPending(true)
    setResetError(null)
    setNotice(null)
    try {
      const response = await runWalletReset(
        { token: accountData.token, accountId: accountData.accountId, requestId: resetRequestId },
        {
          resetDemoAccount: defaultWalletMutationDependencies.resetDemoAccount,
          refresh: accountData.refreshAccountData
        }
      )
      setNotice({ key: 'assets.resetCompleted' })
      return response
    } catch (error) {
      const apiError = toAccountApiError(error)
      setResetError({ key: 'assets.resetFailed', values: { message: apiError.message, code: apiError.code } })
      throw error
    } finally {
      resetInFlight.current = false
      setResetPending(false)
    }
  }, [accountData.accountId, accountData.refreshAccountData, accountData.token, resetRequestId])

  return {
    ...accountData,
    transferDirection,
    transferAmount,
    transferRequestId,
    transferPending,
    transferError,
    resetRequestId,
    resetPending,
    resetError,
    notice,
    setNotice,
    beginTransfer,
    changeTransferDirection,
    changeTransferAmount,
    setTransferRequestId,
    submitTransfer,
    beginReset,
    setResetRequestId,
    submitReset
  }
}
