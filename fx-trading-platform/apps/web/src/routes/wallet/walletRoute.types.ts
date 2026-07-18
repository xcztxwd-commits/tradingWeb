import type { Amount } from '@fx-platform/frontend-core'
import type { useWalletRouteController } from './useWalletRouteController'

export type AssetRow = {
  key: string
  walletType: string
  currency: string
  balance: Amount
  available: Amount
  frozen: Amount
  activityCount: number
}

export type FundOrderForm = {
  orderType: 'RECHARGE' | 'WITHDRAWAL'
  amount: string
  currency: string
  note: string
}

export type WalletRouteDialogState = {
  transferOpen: boolean
  resetOpen: boolean
}

export type WalletRouteModel = ReturnType<typeof useWalletRouteController>
