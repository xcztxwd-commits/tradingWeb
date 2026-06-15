import { Navigate, Outlet, useLocation } from 'react-router-dom'

import { getValidAdminToken } from '../services/adminToken'

export function RequireAdmin() {
  const location = useLocation()
  const token = getValidAdminToken()

  if (!token) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  return <Outlet />
}
