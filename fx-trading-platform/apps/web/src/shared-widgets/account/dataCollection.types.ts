import type { ComponentType, ReactNode } from 'react'
import type { DataViewColumn } from '@fx-platform/ui'

export type AccountDataCollectionProps<T extends object = Record<string, unknown>> = {
  rows: T[]
  columns: Array<DataViewColumn<T>>
  rowKey: (row: T) => string
  emptyMessage: string
  emptyAction?: {
    label: string
    href?: string
    onClick?: () => void
  }
  pageSize?: number
  heading?: ReactNode
}

export type AccountDataCollectionComponent = ComponentType<AccountDataCollectionProps<any>>
