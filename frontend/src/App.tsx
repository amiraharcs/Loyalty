import AppRoutes from './routes/AppRoutes'
import SessionProvider from './features/session/SessionProvider'

export default function App() {
  return <SessionProvider><AppRoutes /></SessionProvider>
}
