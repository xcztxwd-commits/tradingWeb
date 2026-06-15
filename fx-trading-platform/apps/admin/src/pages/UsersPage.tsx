import { getUsersPage, updateUserStatus } from '../services/adminApi'
import { getAdminToken } from '../services/adminToken'
import type { AdminUser } from '../types'
import { AdminPageTable, display, useAdminData } from './adminPageUtils'

const roleLabels: Record<AdminUser['role'], string> = {
  ADMIN: '管理员',
  USER: '普通用户'
}

const statusLabels: Record<AdminUser['status'], string> = {
  ACTIVE: '正常',
  DISABLED: '停用',
  FROZEN: '冻结'
}

export function UsersPage() {
  const { data, loading, error, reload } = useAdminData(getUsersPage)

  const changeStatus = async (user: AdminUser) => {
    const token = getAdminToken()
    if (!token) return
    await updateUserStatus(user.id, user.status === 'ACTIVE' ? 'FROZEN' : 'ACTIVE', token)
    await reload()
  }

  return (
    <AdminPageTable
      title="用户管理"
      description="查看用户状态、实名状态和风险等级，保留现有后台状态更新接口。"
      page={data}
      loading={loading}
      error={error}
      emptyText="暂无用户数据"
      columns={[
        { title: '邮箱', render: (row) => row.email },
        { title: '角色', render: (row) => roleLabels[row.role] },
        { title: '状态', render: (row) => statusLabels[row.status] },
        { title: 'KYC', render: (row) => display(row.kycStatus) },
        { title: '风险', render: (row) => display(row.riskLevel) },
        {
          title: '操作',
          render: (row) =>
            row.role === 'ADMIN' ? (
              '无需操作'
            ) : (
              <button type="button" onClick={() => void changeStatus(row)}>
                {row.status === 'ACTIVE' ? '冻结' : '启用'}
              </button>
            )
        }
      ]}
    />
  )
}
