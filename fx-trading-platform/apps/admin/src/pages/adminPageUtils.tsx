import { useEffect, useRef, useState, type ReactNode } from 'react'

import { getValidAdminToken } from '../services/adminToken'
import type { AdminPage } from '../types'

export type TableColumn<T> = {
  title: string
  render: (row: T) => ReactNode
}

export function useAdminData<T>(loader: (token: string) => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T>()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const generationRef = useRef(0)

  const reload = async () => {
    const generation = ++generationRef.current
    const token = getValidAdminToken()
    if (!token) {
      if (generation !== generationRef.current) return
      setData(undefined)
      setError('登录状态已失效，请重新登录')
      setLoading(false)
      return
    }

    setData(undefined)
    setLoading(true)
    setError('')
    try {
      const next = await loader(token)
      if (generation !== generationRef.current) return
      setData(next)
    } catch (err) {
      if (generation !== generationRef.current) return
      setError(err instanceof Error ? err.message : '数据加载失败')
    } finally {
      if (generation === generationRef.current) setLoading(false)
    }
  }

  useEffect(() => {
    void reload()
    return () => {
      generationRef.current += 1
    }
  }, deps)

  return { data, loading, error, reload }
}

export function PageHeader({ title, description }: { title: string; description: string }) {
  return (
    <section className="page-header">
      <h2>{title}</h2>
      <p>{description}</p>
    </section>
  )
}

type StateBlockTone = 'loading' | 'error' | 'empty'

const stateBlockClasses: Record<StateBlockTone, string> = {
  loading: 'state-block loading',
  error: 'state-block error',
  empty: 'state-block empty'
}

export function StateBlock({ children, tone = 'empty' }: { children: ReactNode; tone?: StateBlockTone }) {
  return <div className={stateBlockClasses[tone]}>{children}</div>
}

export function DataTable<T>({
  columns,
  rows,
  emptyText
}: {
  columns: TableColumn<T>[]
  rows: T[]
  emptyText: string
}) {
  return (
    <section className="admin-table operation-table-scroll">
      <table>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.title}>{column.title}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length}>{emptyText}</td>
            </tr>
          ) : (
            rows.map((row, index) => (
              <tr key={rowKey(row, index)}>
                {columns.map((column) => (
                  <td key={column.title}>{column.render(row)}</td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </section>
  )
}

export function AdminPageTable<T>({
  title,
  description,
  page,
  loading,
  error,
  columns,
  emptyText
}: {
  title: string
  description: string
  page?: AdminPage<T>
  loading: boolean
  error: string
  columns: TableColumn<T>[]
  emptyText: string
}) {
  return (
    <>
      <PageHeader title={title} description={description} />
      {loading ? <StateBlock tone="loading">正在加载数据</StateBlock> : null}
      {error ? <StateBlock tone="error">{error}</StateBlock> : null}
      {!loading && !error ? <DataTable columns={columns} rows={page?.items ?? []} emptyText={emptyText} /> : null}
      {!loading && !error && page ? (
        <div className="page-meta">
          第 {page.page + 1} 页，共 {page.total} 条
        </div>
      ) : null}
    </>
  )
}

export function PlaceholderPage({
  title,
  description,
  contract
}: {
  title: string
  description: string
  contract: string
}) {
  return (
    <>
      <PageHeader title={title} description={description} />
      <section className="placeholder-panel">
        <strong>后端接口待接入</strong>
        <span>{contract}</span>
      </section>
    </>
  )
}

export function formatDateTime(value: string | null | undefined) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function display(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'boolean') return value ? '启用' : '停用'
  return String(value)
}

function rowKey(row: unknown, index: number) {
  if (typeof row === 'object' && row !== null && 'id' in row) {
    return String((row as { id: unknown }).id)
  }
  return String(index)
}
