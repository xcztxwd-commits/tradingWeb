import { getArticlesPage } from '../services/adminApi'
import { AdminPageTable, formatDateTime, useAdminData } from './adminPageUtils'

export function ArticlesPage() {
  const { data, loading, error } = useAdminData(getArticlesPage)

  return (
    <AdminPageTable
      title="公告新闻"
      description="查看后台公告和新闻内容。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无公告新闻"
      columns={[
        { title: '标题', render: (row) => row.title },
        { title: '类型', render: (row) => row.articleType },
        { title: '状态', render: (row) => row.status },
        { title: '语言', render: (row) => row.language },
        { title: '创建时间', render: (row) => formatDateTime(row.createdAt) }
      ]}
    />
  )
}
