import { Outlet } from 'react-router-dom'
import BottomNavigation from './BottomNavigation'

export default function AppShell() {
  return <><Outlet /><BottomNavigation /></>
}
