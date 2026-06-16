import { useTranslation } from 'react-i18next'

import type { PositionResponse } from '../../../components/tables/types'
import { EmptyState } from './BottomAccountEmptyState'
import { createPositionDisplayRow } from './positionDisplayModel'
import styles from './BottomAccountPanel.module.css'

type Props = {
  positions: PositionResponse[]
  emptyLabel: string
  mode?: 'current' | 'history'
  onClosePosition?: (position: PositionResponse) => Promise<unknown> | void
}

export function PositionsGrid({ positions, emptyLabel, mode = 'current', onClosePosition }: Props) {
  const { t } = useTranslation()

  if (positions.length === 0) return <EmptyState label={emptyLabel} />

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>{t('positions.instrument')}</th>
          <th>{t('positions.positionSize')}</th>
          <th>{t('positions.markPrice')}</th>
          <th>{t('positions.openAveragePrice')}</th>
          <th>{t('positions.estimatedLiquidationPrice')}</th>
          <th>{t('positions.breakEvenPrice')}</th>
          <th>{mode === 'history' ? t('positions.realizedPnl') : t('positions.floatingPnl')}</th>
          <th>{t('positions.maintenanceMargin')}</th>
          <th>{t('positions.margin')}</th>
          <th>{t('positions.takeProfitStopLoss')}</th>
          <th>{t('common.action')}</th>
        </tr>
      </thead>
      <tbody>
        {positions.map((position) => {
          const row = createPositionDisplayRow(position, t)
          const pnlValue = mode === 'history' ? row.realizedPnl : row.floatingPnl
          const pnlTone = mode === 'history' ? row.realizedPnlTone : row.floatingPnlTone
          const pnlClass =
            pnlTone === 'positive'
              ? styles.positiveValue
              : pnlTone === 'negative'
                ? styles.negativeValue
                : undefined

          return (
            <tr key={position.id}>
              <td>
                <span className={styles.positionSymbol}>{row.instrument}</span>
                <span className={styles.positionMeta}>{row.leverage}</span>
              </td>
              <td>{row.quantity}</td>
              <td>{row.markPrice}</td>
              <td>{row.openPrice}</td>
              <td>{row.liquidationPrice}</td>
              <td>{row.breakEvenPrice}</td>
              <td className={pnlClass}>{pnlValue}</td>
              <td>{row.showMaintenanceMargin ? row.maintenanceMargin : null}</td>
              <td>
                <span>{row.margin}</span>
                <span className={styles.positionMeta}>{row.marginMode}</span>
              </td>
              <td>
                {row.takeProfit !== '--' || row.stopLoss !== '--' ? (
                  <span className={styles.protectionValues}>
                    <span className={styles.positiveValue}>{row.takeProfit}</span>
                    <span className={styles.negativeValue}>{row.stopLoss}</span>
                  </span>
                ) : (
                  '--'
                )}
              </td>
              <td>
                {onClosePosition && row.canClose ? (
                  <span className={styles.actionGroup}>
                    <button type="button" className={styles.closeButton} onClick={() => void onClosePosition(position)}>
                      {t('positions.closePosition')}
                    </button>
                    <button type="button" className={styles.closeButton} onClick={() => void onClosePosition(position)}>
                      {t('positions.closeAllMarket')}
                    </button>
                  </span>
                ) : (
                  '-'
                )}
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
