import { getAuditLogsPage } from '../services/adminApi'
import { AdminPageTable, display, formatDateTime, useAdminData } from './adminPageUtils'

export function AuditLogsPage() {
  const { data, loading, error } = useAdminData(getAuditLogsPage)

  return (
    <AdminPageTable
      title="审计日志"
      description="查看后台写操作审计记录，审计数据由 Java 后台统一写入。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无审计日志"
      columns={[
        { title: '动作', render: (row) => row.action },
        { title: '对象', render: (row) => display(row.targetType) },
        { title: '目标 ID', render: (row) => display(row.targetId) },
        { title: '操作者', render: (row) => display(row.actorUserId) },
        { title: '时间', render: (row) => formatDateTime(row.createdAt) }
      ]}
    />
  )
}
