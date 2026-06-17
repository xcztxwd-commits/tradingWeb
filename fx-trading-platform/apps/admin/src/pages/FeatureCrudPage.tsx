import { useEffect, useMemo, useState } from 'react'
import {
  Download,
  Eye,
  FileDown,
  FileUp,
  Maximize2,
  Plus,
  RefreshCw,
  Save,
  Search,
  Settings,
  Trash2,
  X
} from 'lucide-react'

import { getFeaturePage, getTableColumnPreference, runFeatureAction, saveTableColumnPreference } from '../services/adminApi'
import { getAdminAuthorities, getValidAdminToken } from '../services/adminToken'
import type { AdminFeatureAction, AdminFeatureField, AdminFeaturePage } from '../types'
import { StateBlock, useAdminData } from './adminPageUtils'

type DialogState = {
  action: AdminFeatureAction
  row?: Record<string, unknown>
}

type TableSize = 'mini' | 'small' | 'middle' | 'large'

const ACTION_PERMISSION_MAP: Record<string, string> = {
  'products:create': 'market:symbol:create',
  'products:edit': 'market:symbol:update',
  'products:risk': 'market:symbol:update',
  'products:delete': 'market:symbol:disable',
  'price-schedules:create': 'finance:adjustment:create',
  'price-schedules:cancel': 'finance:adjustment:create',
  'recharge-orders:review': 'finance:fund-order:approve',
  'withdrawal-orders:review': 'finance:fund-order:approve',
  'order-history:cancel': 'trading:order:cancel',
  'order-history:close-position': 'trading:position:force-close',
  'members:edit': 'user:update',
  'members:kick-offline': 'user:force-logout',
  'members:real-name': 'user:update',
  'members:remark': 'user:update',
  'members:one-click-profit': 'user:update',
  'members:one-click-normal': 'user:update',
  'members:send-message': 'user:update'
}

const ACTION_CONFIRMATION_MAP: Record<string, string> = {
  'recharge-orders:review': 'CONFIRM_APPROVE',
  'withdrawal-orders:review': 'CONFIRM_APPROVE',
  'order-history:close-position': 'CONFIRM_FORCE_CLOSE'
}

export function FeatureCrudPage({ pageKey }: { pageKey: string }) {
  const [filters, setFilters] = useState<Record<string, string>>({})
  const [queryFilters, setQueryFilters] = useState<Record<string, string>>({})
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(10)
  const [sortField, setSortField] = useState('')
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('asc')
  const [dialog, setDialog] = useState<DialogState>()
  const [formValues, setFormValues] = useState<Record<string, string>>({})
  const [message, setMessage] = useState('')
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [hiddenColumns, setHiddenColumns] = useState<Set<string>>(new Set())
  const [tableSize, setTableSize] = useState<TableSize>('large')
  const [showBorder, setShowBorder] = useState(false)
  const [zebra, setZebra] = useState(true)
  const [preferenceReady, setPreferenceReady] = useState(false)
  const authorities = useMemo(() => new Set(getAdminAuthorities()), [])
  const { data, loading, error, reload } = useAdminData<AdminFeaturePage>(
    (token) =>
      getFeaturePage(token, pageKey, {
        page,
        size: pageSize,
        filters: queryFilters,
        sortField: sortField || undefined,
        sortDirection: sortField ? sortDirection : undefined
      }),
    [pageKey, page, pageSize, queryFilters, sortField, sortDirection]
  )

  useEffect(() => {
    setFilters({})
    setQueryFilters({})
    setPage(0)
    setPageSize(10)
    setSortField('')
    setSortDirection('asc')
    setMessage('')
    setHiddenColumns(new Set())
    setPreferenceReady(false)
  }, [pageKey])

  useEffect(() => {
    const token = getValidAdminToken()
    if (!token) {
      setPreferenceReady(true)
      return undefined
    }
    let canceled = false
    getTableColumnPreference(token, pageKey)
      .then((preference) => {
        if (canceled) return
        if (preference) {
          setHiddenColumns(new Set(preference.hiddenColumns))
          setTableSize(normalizeTableSize(preference.tableSize))
          setShowBorder(Boolean(preference.showBorder))
          setZebra(preference.zebra !== false)
        }
        setPreferenceReady(true)
      })
      .catch(() => setPreferenceReady(true))
    return () => {
      canceled = true
    }
  }, [pageKey])

  const hiddenColumnKeys = useMemo(() => Array.from(hiddenColumns).sort(), [hiddenColumns])

  useEffect(() => {
    if (!preferenceReady) return undefined
    const token = getValidAdminToken()
    if (!token) return undefined
    const timer = window.setTimeout(() => {
      void saveTableColumnPreference(token, pageKey, {
        hiddenColumns: hiddenColumnKeys,
        tableSize,
        showBorder,
        zebra
      })
    }, 350)
    return () => window.clearTimeout(timer)
  }, [hiddenColumnKeys, pageKey, preferenceReady, showBorder, tableSize, zebra])

  const visibleColumns = useMemo(() => {
    return (data?.columns ?? []).filter((column) => !hiddenColumns.has(column.key))
  }, [data?.columns, hiddenColumns])

  const visibleToolbarActions = useMemo(() => {
    return (data?.toolbarActions ?? []).filter((action) => canRunAction(pageKey, action, undefined, authorities))
  }, [authorities, data?.toolbarActions, pageKey])

  const visibleRowActions = (row: Record<string, unknown>) => {
    return (data?.rowActions ?? []).filter((action) => canRunAction(pageKey, action, row, authorities))
  }

  const openDialog = (action: AdminFeatureAction, row?: Record<string, unknown>) => {
    if (isImmediateAction(action) && !mayRequireConfirmation(pageKey, action, row)) {
      void executeAction(action, row)
      return
    }
    setDialog({ action, row })
    setFormValues(initialFormValues(actionFields(action.key, data?.fields ?? []), row, mayRequireConfirmation(pageKey, action, row)))
  }

  const executeAction = async (action: AdminFeatureAction, row?: Record<string, unknown>, payload?: Record<string, unknown>) => {
    const token = getValidAdminToken()
    if (!token) {
      setMessage('登录状态已失效，请重新登录')
      return
    }

    try {
      const result = await runFeatureAction(token, pageKey, {
        action: action.key,
        rowId: rowId(row),
        reason: '后台管理操作',
        payload
      })
      setMessage(result.message)
      setDialog(undefined)
      await reload()
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '操作失败')
    }
  }

  const submitDialog = () => {
    if (!dialog) return
    const requiredText = requiredConfirmationText(pageKey, dialog.action, dialog.row, formValues)
    if (requiredText && formValues.confirmationText?.trim() !== requiredText) {
      setMessage(`请输入确认码 ${requiredText}`)
      return
    }
    void executeAction(dialog.action, dialog.row, actionPayload(formValues, requiredText))
  }

  if (loading) return <StateBlock tone="loading">正在加载数据</StateBlock>
  if (error) return <StateBlock tone="error">{error}</StateBlock>
  if (!data) return <StateBlock>暂无数据</StateBlock>

  const rows = data.rows ?? []
  const totalRows = data.total ?? rows.length
  const totalPages = Math.max(1, data.totalPages ?? 1)
  const dialogFields = dialog ? actionFields(dialog.action.key, data.fields) : []
  const dialogRequiresConfirmation = dialog ? mayRequireConfirmation(pageKey, dialog.action, dialog.row) : false

  return (
    <section className="feature-page">
      <header className="feature-page-header">
        <div>
          <span className="feature-page-kicker">{data.group}</span>
          <h1>{data.title}</h1>
          <p>筛选、表格视图和行级操作会同步当前后台接口数据。</p>
        </div>
        <div className="feature-page-metrics" aria-label="当前表格概览">
          <span>
            <strong>{totalRows.toLocaleString()}</strong>
            总记录
          </span>
          <span>
            <strong>{visibleColumns.length}</strong>
            可见列
          </span>
          <span>
            <strong>{pageSize}</strong>
            每页
          </span>
        </div>
      </header>

      <form
        className="feature-filters"
        onSubmit={(event) => {
          event.preventDefault()
          setPage(0)
          setQueryFilters(trimFilters(filters))
          setMessage(`搜索：${data.title}`)
        }}
      >
        {data.fields.map((field) => (
          <label key={field.key} className="feature-field">
            <span>{field.label}</span>
            <FieldControl
              field={field}
              value={filters[field.key] ?? ''}
              onChange={(value) => setFilters((current) => ({ ...current, [field.key]: value }))}
            />
          </label>
        ))}
        <div className="feature-filter-actions">
          <button type="submit" className="primary-button">
            <Search size={16} />
            搜索
          </button>
          <button
            type="button"
            className="ghost-button"
            onClick={() => {
              setFilters({})
              setQueryFilters({})
              setPage(0)
              setSortField('')
              setSortDirection('asc')
              setMessage('筛选条件已重置')
            }}
          >
            <Trash2 size={15} />
            重置
          </button>
        </div>
      </form>

      <div className="feature-toolbar">
        <div className="feature-toolbar-left">
          {visibleToolbarActions.map((action) => (
            <button
              key={action.key}
              type="button"
              className={toolbarButtonClass(action)}
              title={`执行${action.label}，支持新增、删除、导入、导出`}
              onClick={() => openDialog(action)}
            >
              <ActionIcon action={action} />
              {action.label}
            </button>
          ))}
          <button type="button" className="ghost-button" onClick={() => openDialog({ key: 'import', label: '导入', type: 'upload' })}>
            <FileUp size={15} />
            导入
          </button>
          <button type="button" className="ghost-button" onClick={() => openDialog({ key: 'export', label: '导出', type: 'download' })}>
            <FileDown size={15} />
            导出
          </button>
          <button type="button" className="ghost-button" onClick={() => setMessage('树形数据已展开')}>
            <Maximize2 size={15} />
            展开
          </button>
        </div>
        <div className="feature-toolbar-right">
          <button type="button" className="circle-button" title="刷新" onClick={() => void reload()}>
            <RefreshCw size={16} />
          </button>
          <button type="button" className="circle-button" title="表格设置" onClick={() => setSettingsOpen(true)}>
            <Settings size={16} />
          </button>
          <button type="button" className="ghost-button" onClick={() => setSettingsOpen(true)}>
            表格设置
          </button>
        </div>
      </div>

      {message ? <div className="feature-message">{message}</div> : null}

      <div className={`feature-table feature-table-${tableSize} ${showBorder ? 'with-border' : ''} ${zebra ? 'with-zebra' : ''}`}>
        <table>
          <thead>
            <tr>
              <th className="select-column">
                <input type="checkbox" aria-label="全选" />
              </th>
              {visibleColumns.map((column) => (
                <th key={column.key}>
                  <button
                    type="button"
                    className="sort-button"
                    disabled={!column.sortable}
                    onClick={() => {
                      if (!column.sortable) return
                      setPage(0)
                      setSortField(column.key)
                      setSortDirection((current) => (sortField === column.key && current === 'asc' ? 'desc' : 'asc'))
                    }}
                  >
                    {column.label}
                    {column.sortable ? (
                      <span className="sort-mark">{sortField === column.key ? (sortDirection === 'asc' ? '↑' : '↓') : '↕'}</span>
                    ) : null}
                  </button>
                </th>
              ))}
              <th className="action-column">操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={visibleColumns.length + 2}>
                  <div className="empty-state">暂无数据</div>
                </td>
              </tr>
            ) : (
              rows.map((row, index) => (
                <tr key={rowId(row) ?? index}>
                  <td className="select-column">
                    <input type="checkbox" aria-label="选择行" />
                  </td>
                  {visibleColumns.map((column) => (
                    <td key={column.key}>{formatCell(row[column.key])}</td>
                  ))}
                  <td className="row-actions">
                    {visibleRowActions(row).map((action) => (
                      <button key={action.key} type="button" title={`执行${action.label}，支持审核、编辑、删除`} onClick={() => openDialog(action, row)}>
                        {action.label}
                      </button>
                    ))}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="feature-pagination">
        <span>共 {totalRows} 条</span>
        <button type="button" disabled={page <= 0} onClick={() => setPage((current) => Math.max(0, current - 1))}>
          上一页
        </button>
        <button type="button" aria-label="当前页">
          {page + 1} / {totalPages}
        </button>
        <button type="button" disabled={page + 1 >= totalPages} onClick={() => setPage((current) => current + 1)}>
          下一页
        </button>
        <select
          value={String(pageSize)}
          onChange={(event) => {
            setPage(0)
            setPageSize(Number(event.target.value))
          }}
        >
          <option value="10">10 条/页</option>
          <option value="20">20 条/页</option>
          <option value="50">50 条/页</option>
        </select>
        <span>前往</span>
        <input
          aria-label="跳转页码"
          type="number"
          min="1"
          max={totalPages}
          onKeyDown={(event) => {
            if (event.key !== 'Enter') return
            const nextPage = Number(event.currentTarget.value)
            if (Number.isNaN(nextPage)) return
            setPage(Math.min(Math.max(nextPage - 1, 0), totalPages - 1))
          }}
        />
      </div>

      {dialog ? (
        <div className="modal-mask">
          <div className="feature-modal">
            <header>
              <h2>{dialog.action.label}</h2>
              <button type="button" onClick={() => setDialog(undefined)} aria-label="关闭">
                <X size={18} />
              </button>
            </header>
            <div className="feature-modal-body">
              {dialogFields.map((field) => (
                <label key={field.key} className="feature-field">
                  <span>{field.label}</span>
                  <FieldControl
                    field={field}
                    value={formValues[field.key] ?? ''}
                    onChange={(value) => setFormValues((current) => ({ ...current, [field.key]: value }))}
                  />
                </label>
              ))}
              {dialogRequiresConfirmation ? (
                <label className="feature-field">
                  <span>确认码</span>
                  <input
                    value={formValues.confirmationText ?? ''}
                    placeholder="请输入确认码"
                    onChange={(event) => setFormValues((current) => ({ ...current, confirmationText: event.target.value }))}
                  />
                </label>
              ) : null}
            </div>
            <footer>
              <button type="button" className="ghost-button" onClick={() => setDialog(undefined)}>
                关闭
              </button>
              <button type="button" className="primary-button" onClick={submitDialog}>
                <Save size={16} />
                保存
              </button>
            </footer>
          </div>
        </div>
      ) : null}

      {settingsOpen ? (
        <div className="modal-mask">
          <div className="settings-modal">
            <header>
              <h2>设置</h2>
              <button type="button" onClick={() => setSettingsOpen(false)} aria-label="关闭">
                <X size={18} />
              </button>
            </header>
            <div className="settings-grid">
              <div>
                <span>表格大小:</span>
                {(['mini', 'small', 'middle', 'large'] as TableSize[]).map((size) => (
                  <button
                    key={size}
                    type="button"
                    className={tableSize === size ? 'selected' : ''}
                    onClick={() => setTableSize(size)}
                  >
                    {sizeLabel(size)}
                  </button>
                ))}
              </div>
              <label>
                <input type="checkbox" checked={showBorder} onChange={(event) => setShowBorder(event.target.checked)} />
                全部显示边框
              </label>
              <label>
                <input type="checkbox" checked={zebra} onChange={(event) => setZebra(event.target.checked)} />
                斑马纹
              </label>
            </div>
            <table className="settings-table">
              <thead>
                <tr>
                  <th>列名称</th>
                  <th>隐藏</th>
                  <th>固定</th>
                  <th>排序</th>
                </tr>
              </thead>
              <tbody>
                {data.columns.map((column) => (
                  <tr key={column.key}>
                    <td>{column.label}</td>
                    <td>
                      <input
                        type="checkbox"
                        checked={hiddenColumns.has(column.key)}
                        onChange={(event) => {
                          setHiddenColumns((current) => {
                            const next = new Set(current)
                            if (event.target.checked) next.add(column.key)
                            else next.delete(column.key)
                            return next
                          })
                        }}
                      />
                    </td>
                    <td>无 / 左 / 右</td>
                    <td>{column.sortable ? '本页 / 服务器' : '无'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
    </section>
  )
}

function canRunAction(
  pageKey: string,
  action: AdminFeatureAction,
  row: Record<string, unknown> | undefined,
  authorities: Set<string>
) {
  const permission = requiredActionPermission(pageKey, action, row)
  return !permission || authorities.has(permission)
}

function requiredActionPermission(pageKey: string, action: AdminFeatureAction, row?: Record<string, unknown>) {
  if (pageKey === 'members' && action.key === 'edit' && row?.status === 'DISABLED') return 'user:disable'
  return ACTION_PERMISSION_MAP[`${pageKey}:${action.key}`]
}

function mayRequireConfirmation(pageKey: string, action: AdminFeatureAction, row?: Record<string, unknown>) {
  if (ACTION_CONFIRMATION_MAP[`${pageKey}:${action.key}`]) return true
  if (pageKey === 'products' && action.key === 'risk') return true
  if (pageKey === 'products' && action.key === 'edit' && row?.leverage !== undefined) return true
  if (pageKey === 'members' && action.key === 'edit') return true
  return false
}

function requiredConfirmationText(
  pageKey: string,
  action: AdminFeatureAction,
  row: Record<string, unknown> | undefined,
  payload: Record<string, string>
) {
  const mapped = ACTION_CONFIRMATION_MAP[`${pageKey}:${action.key}`]
  if (mapped) return mapped
  if (pageKey === 'products' && action.key === 'risk') {
    return payload.enabled === 'false' || payload.status === 'false' ? 'CONFIRM_DISABLE_SYMBOL' : ''
  }
  if (pageKey === 'products' && action.key === 'edit' && row?.leverage !== undefined) {
    return String(payload.leverage ?? '') !== String(row.leverage ?? '') ? 'CONFIRM_LEVERAGE_CHANGE' : ''
  }
  if (pageKey === 'members' && action.key === 'edit') {
    return payload.status === 'DISABLED' ? 'CONFIRM_DISABLE_USER' : ''
  }
  return ''
}

function actionPayload(values: Record<string, string>, requiredConfirmationText: string) {
  const { confirmationText, ...payload } = values
  return requiredConfirmationText ? { ...payload, confirmationText: confirmationText?.trim() } : payload
}

function isImmediateAction(action: AdminFeatureAction) {
  return action.type === 'confirm' || action.type === 'download' || action.type === 'toggle' || action.type === 'reset'
}

function FieldControl({
  field,
  value,
  onChange
}: {
  field: AdminFeatureField
  value: string
  onChange: (value: string) => void
}) {
  if (field.component === 'select') {
    return (
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">请选择{field.label}</option>
        {field.options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    )
  }

  if (field.component === 'textarea' || field.component === 'richtext') {
    return <textarea value={value} placeholder={`请输入${field.label}`} onChange={(event) => onChange(event.target.value)} />
  }

  const type = field.component === 'password' ? 'password' : field.component === 'number' ? 'number' : 'text'
  return <input type={type} value={value} placeholder={`请输入${field.label}`} onChange={(event) => onChange(event.target.value)} />
}

function actionFields(actionKey: string, fields: AdminFeatureField[]) {
  if (actionKey === 'menu-permissions') {
    return [
      { key: 'menuId', label: '菜单ID', component: 'input', options: [] },
      { key: 'buttons', label: '按钮权限', component: 'textarea', options: [] }
    ]
  }
  if (actionKey === 'data-scope') {
    return [
      {
        key: 'scopeType',
        label: '数据范围',
        component: 'select',
        options: [
          { label: '全部数据权限', value: 'ALL' },
          { label: '本部门数据权限', value: 'DEPARTMENT' },
          { label: '本部门及以下数据权限', value: 'DEPARTMENT_AND_CHILDREN' },
          { label: '仅本人数据权限', value: 'SELF' }
        ]
      },
      { key: 'departmentIds', label: '部门ID列表', component: 'textarea', options: [] }
    ]
  }
  if (actionKey === 'risk') {
    return [
      { key: 'targetPrice', label: '目标价格', component: 'number', options: [] },
      {
        key: 'enabled',
        label: '产品状态',
        component: 'select',
        options: [
          { label: '启用', value: 'true' },
          { label: '停用', value: 'false' }
        ]
      },
      { key: 'reason', label: '原因', component: 'textarea', options: [] }
    ]
  }
  if (actionKey === 'cancel') {
    return [
      { key: 'reason', label: '撤销原因', component: 'textarea', options: [] }
    ]
  }
  return fields
}

function initialFormValues(fields: AdminFeatureField[], row?: Record<string, unknown>, includeConfirmation = false) {
  const values: Record<string, string> = {}
  for (const field of fields) {
    const value = row?.[field.key]
    values[field.key] = value === null || value === undefined ? '' : String(value)
  }
  if (includeConfirmation) {
    values.confirmationText = ''
  }
  return values
}

function trimFilters(filters: Record<string, string>) {
  return Object.fromEntries(Object.entries(filters).map(([key, value]) => [key, value.trim()]).filter(([, value]) => value))
}

function rowId(row?: Record<string, unknown>) {
  if (!row) return undefined
  return row.id === undefined ? undefined : String(row.id)
}

function formatCell(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value)
}

function toolbarButtonClass(action: AdminFeatureAction) {
  if (action.key === 'delete') return 'danger-button'
  if (action.key === 'create' || action.key === 'submit') return 'primary-button'
  return 'ghost-button'
}

function ActionIcon({ action }: { action: AdminFeatureAction }) {
  if (action.key === 'create') return <Plus size={16} />
  if (action.key === 'delete') return <Trash2 size={16} />
  if (action.key === 'export') return <Download size={16} />
  if (action.key === 'submit') return <Save size={16} />
  if (action.key === 'review') return <Eye size={16} />
  return <Settings size={16} />
}

function normalizeTableSize(size: string): TableSize {
  if (size === 'mini' || size === 'small' || size === 'middle' || size === 'large') return size
  return 'large'
}

function sizeLabel(size: TableSize) {
  if (size === 'mini') return '迷你'
  if (size === 'small') return '小'
  if (size === 'middle') return '中'
  return '大'
}
