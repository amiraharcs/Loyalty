import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { LoyaltyMember } from '../../types/loyalty'
import { ApiError, resetCsrf } from '../../api/client'
import { getCurrentUser, login, logout } from '../../api/auth'
import type { CurrentUser } from '../../api/auth'
import { SessionContext } from './SessionContext'

// Other loyalty screens remain prototype fixtures; these values never enter game APIs.
const presentation = { points: 2450, streak: 7, maxStreak: 10, nextReward: 3000, tier: 'Premium Member' }
const memberFor = (user: CurrentUser, email = ''): LoyaltyMember => ({ ...presentation, name: user.displayName || 'Member', email })
export default function SessionProvider({ children }: { children: ReactNode }) {
  const [member, setMember] = useState<LoyaltyMember | null>(null)
  const [status, setStatus] = useState<'loading' | 'authenticated' | 'anonymous' | 'error'>('loading')
  const [error, setError] = useState('')
  const revision = useRef(0)
  const invalidate = useCallback(() => { revision.current++ }, [])
  const reload = useCallback(async () => {
    const current = ++revision.current
    setStatus('loading'); setError('')
    try {
      const user = await getCurrentUser()
      if (current !== revision.current) return
      setMember(memberFor(user)); setStatus('authenticated')
    }
    catch (cause) {
      if (current !== revision.current) return
      setMember(null)
      if (cause instanceof ApiError && cause.status === 401) setStatus('anonymous')
      else { setStatus('error'); setError('Could not check your session. Please retry.') }
    }
  }, [])
  useEffect(() => {
    void reload()
    const expired = () => { revision.current++; resetCsrf(); setMember(null); setStatus('anonymous') }
    window.addEventListener('loyalty:unauthenticated', expired)
    return () => { invalidate(); window.removeEventListener('loyalty:unauthenticated', expired) }
  }, [reload, invalidate])
  return <SessionContext.Provider value={{ member, status, error, reload,
    signIn: async (email, password) => { const current = ++revision.current; const user = await login(email, password); if (current !== revision.current) return; setMember(memberFor(user, email)); setStatus('authenticated'); setError('') },
    signOut: async () => { revision.current++; await logout(); setMember(null); setStatus('anonymous') },
    addPoints: amount => setMember(current => current ? { ...current, points: current.points + amount } : null),
  }}>{children}</SessionContext.Provider>
}
