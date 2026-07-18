import type { ReactNode } from 'react'

import { renderCell, type DataViewColumn } from './DataTable'
import styles from './DataView.module.css'

export type DataCardListProps<T extends object> = {
  rows: readonly T[]
  columns: readonly DataViewColumn<T>[]
  rowKey: (row: T) => string
  ariaLabel: string
  empty?: ReactNode
}

export function DataCardList<T extends object>({ rows, columns, rowKey, ariaLabel, empty }: DataCardListProps<T>) {
  return (
    <div className={styles.cards} role="list" aria-label={ariaLabel}>
      {rows.length === 0 ? (
        <div className={styles.empty}>{empty}</div>
      ) : (
        rows.map((row) => (
          <article key={rowKey(row)} className={styles.card} role="listitem">
            {columns.map((column) => (
              <div key={String(column.key)} className={styles.cardRow}>
                <span className={styles.cardLabel}>{column.label}</span>
                <div className={styles.cardValue}>{renderCell(row, column)}</div>
              </div>
            ))}
          </article>
        ))
      )}
    </div>
  )
}
