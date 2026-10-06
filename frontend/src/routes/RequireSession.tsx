import { Navigate, Outlet } from 'react-router-dom'
import { useSession } from '../features/session/SessionContext'

export default function RequireSession() {
  const { member } = useSession()
  return member ? <Outlet /> : <Navigate to="/login" replace />
}
