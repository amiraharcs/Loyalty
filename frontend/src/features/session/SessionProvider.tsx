import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import type { LoyaltyMember } from '../../types/loyalty'
import { readStored, writeStored } from '../../utils/storage'
import { SessionContext } from './SessionContext'

const demoMember: LoyaltyMember = {
  name: 'Haroon', email: 'haroon@grnd.coffee', points: 2450,
  streak: 7, maxStreak: 10, nextReward: 3000, tier: 'Premium Member',
}
const storageKey = 'loyalty.member.v1'

function initialMember(): LoyaltyMember | null {
  const stored = readStored<LoyaltyMember | null>(storageKey, demoMember)
  if (stored === null) return null
  return typeof stored.name === 'string' && typeof stored.points === 'number'
    ? { ...demoMember, ...stored, name: 'Haroon' } : demoMember
}

export default function SessionProvider({ children }: { children: ReactNode }) {
  const [member, setMember] = useState(initialMember)
  useEffect(() => writeStored(storageKey, member), [member])

  return (
    <SessionContext.Provider value={{
      member,
      signIn: (email) => setMember({ ...demoMember, email }),
      signOut: () => setMember(null),
      addPoints: (amount) => setMember(current => current
        ? { ...current, points: current.points + amount } : null),
    }}>
      {children}
    </SessionContext.Provider>
  )
}
