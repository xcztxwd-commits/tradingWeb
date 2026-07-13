import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

import type { BatchActionResponse } from '@fx-platform/shared-types'
import type { BottomAccountTab } from './bottomAccountTabs'
import { getBottomAccountBatchAction } from './bottomAccountPanelData'
import type { BottomAccountTabView } from './bottomAccountPanelData'
import styles from './BottomAccountPanel.module.css'

type Props = {
  activeTab: BottomAccountTab
  activeView: BottomAccountTabView
  sessionReady: boolean
  onCancelAllOrders?: () => Promise<BatchActionResponse>
  onCloseAllPositions?: () => Promise<BatchActionResponse>
}

export function BottomAccountBatchAction({
  activeTab,
  activeView,
  sessionReady,
  onCancelAllOrders,
  onCloseAllPositions
}: Props) {
  const { t } = useTranslation()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const action = getBottomAccountBatchAction(activeTab, activeView, sessionReady, pending)
  useEffect(() => setError(null), [action?.kind])
  if (!action) return null

  const isCancelAll = action.kind === 'cancel-all-orders'
  const handler = isCancelAll ? onCancelAllOrders : onCloseAllPositions
  const label = t(isCancelAll ? 'orders.cancelAll' : 'orders.closeAllPositions')

  const submit = async () => {
    if (action.disabled || pending || !handler) return
    const confirmKey = isCancelAll ? 'orders.cancelAllConfirm' : 'orders.closeAllPositionsConfirm'
    if (!window.confirm(t(confirmKey))) return
    setPending(true)
    setError(null)
    try {
      await handler()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('orders.batchActionFailed'))
    } finally {
      setPending(false)
    }
  }

  return (
    <div className={styles.batchAction}>
      <button
        type="button"
        className={styles.batchButton}
        disabled={action.disabled || pending || !handler}
        onClick={() => void submit()}
      >
        {label}
      </button>
      {error ? <span className={styles.batchError} role="alert">{error}</span> : null}
    </div>
  )
}
