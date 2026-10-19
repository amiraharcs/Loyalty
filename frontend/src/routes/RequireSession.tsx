import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useSession } from '../features/session/SessionContext'
import Button from '../components/ui/Button'

export default function RequireSession() {
  const { status, error, reload } = useSession()
  const location = useLocation()
  if (status === 'loading') return <main className="p-6" role="status">Checking your session…</main>
  if (status === 'error') return <main className="space-y-4 p-6"><p role="alert">{error}</p><Button onClick={() => void reload()}>Retry</Button></main>
  return status === 'authenticated' ? <Outlet /> : <Navigate to="/login" state={{ from: location.pathname }} replace />
}
