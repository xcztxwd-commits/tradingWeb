import { getSettingsPage } from '../services/adminApi'
import { AdminPageTable, display, useAdminData } from './adminPageUtils'

export function SettingsPage() {
  const { data, loading, error } = useAdminData(getSettingsPage)

  return (
    <AdminPageTable
      title="系统设置"
      description="查看 Java 后台系统设置项。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无系统设置"
      columns={[
        { title: '配置键', render: (row) => row.settingKey },
        { title: '配置值', render: (row) => row.settingValue },
        { title: '类型', render: (row) => row.valueType },
        { title: '可编辑', render: (row) => display(row.editable) }
      ]}
    />
  )
}
