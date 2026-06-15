import { getMessagesPage } from '../services/adminApi'
import { AdminPageTable, formatDateTime, useAdminData } from './adminPageUtils'

export function MessagesPage() {
  const { data, loading, error } = useAdminData(getMessagesPage)

  return (
    <AdminPageTable
      title="站内消息"
      description="查看后台创建的站内消息。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无站内消息"
      columns={[
        { title: '标题', render: (row) => row.title },
        { title: '类型', render: (row) => row.messageType },
        { title: '状态', render: (row) => row.status },
        { title: '目标用户', render: (row) => row.targetUserId ?? '-' },
        { title: '创建时间', render: (row) => formatDateTime(row.createdAt) }
      ]}
    />
  )
}
