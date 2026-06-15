import { getDictionariesPage } from '../services/adminApi'
import { AdminPageTable, display, useAdminData } from './adminPageUtils'

export function DictionariesPage() {
  const { data, loading, error } = useAdminData(getDictionariesPage)

  return (
    <AdminPageTable
      title="字典配置"
      description="查看 Java 后台系统字典配置。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无字典配置"
      columns={[
        { title: '分组', render: (row) => row.groupKey },
        { title: '键', render: (row) => row.itemKey },
        { title: '值', render: (row) => row.itemValue },
        { title: '状态', render: (row) => display(row.enabled) },
        { title: '排序', render: (row) => display(row.displayOrder) }
      ]}
    />
  )
}
