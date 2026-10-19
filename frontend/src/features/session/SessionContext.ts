import { createContext, useContext } from 'react'
import type { LoyaltyMember } from '../../types/loyalty'

interface Session {
  member: LoyaltyMember | null
  status: 'loading' | 'authenticated' | 'anonymous' | 'error'
  error: string
  reload: () => Promise<void>
  signIn: (email: string, password: string) => Promise<void>
  signOut: () => Promise<void>
  addPoints: (amount: number) => void
}
export const SessionContext = createContext<Session | null>(null)
export function useSession() {
  const context = useContext(SessionContext)
  if (!context) throw new Error('useSession requires SessionProvider')
  return context
}
