import { CreditCard, Gamepad2, House } from 'lucide-react'
import { NavLink } from 'react-router-dom'

const items = [
  { to: '/home', label: 'Home', Icon: House },
  { to: '/card', label: 'Card', Icon: CreditCard },
  { to: '/game', label: 'Game', Icon: Gamepad2 },
]

export default function BottomNavigation() {
  return (
    <nav className="bottom-navigation" aria-label="Main navigation">
      <ul className="flex items-center justify-around">
        {items.map(({ to, label, Icon }) => (
          <li key={to}>
            <NavLink to={to} className={({ isActive }) => `nav-item ${isActive ? 'nav-item--active' : ''}`}>
              {({ isActive }) => <>
                <Icon size={24} strokeWidth={isActive ? 2.5 : 1.8} aria-hidden="true" />
                <span className="text-[11px]">{label}</span>
              </>}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
