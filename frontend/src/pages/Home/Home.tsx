import { useCallback, useState } from 'react'
import BrandLogo from '../../components/layout/BrandLogo'
import PointsBalance from '../../components/loyalty/PointsBalance'
import StreakCard from '../../components/loyalty/StreakCard'
import ProfileSheet from '../../components/loyalty/ProfileSheet'
import { useSession } from '../../features/session/SessionContext'
import MascotAnimation from './MascotAnimation'

export default function Home() {
  const { member } = useSession()
  const [profileOpen, setProfileOpen] = useState(false)
  const closeProfile = useCallback(() => setProfileOpen(false), [])
  const hour = new Date().getHours()
  const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening'
  if (!member) return null
  return (
    <main className="page">
      <header className="flex items-center justify-between px-6 pt-14 pb-6">
        <BrandLogo />
        <button aria-label="Open profile" onClick={() => setProfileOpen(true)} className="flex h-9 w-9 items-center justify-center rounded-full bg-[#1A1A1A] text-[13px] font-semibold text-white transition-transform active:scale-95">{member.name[0]}</button>
      </header>
      <div className="space-y-6 px-6">
        <div><p className="text-[14px] text-muted">Welcome back</p><h1 className="text-[28px] leading-tight font-semibold">{greeting}, {member.name.split(' ')[0]}</h1></div>
        <PointsBalance member={member} />
        <StreakCard member={member} />
        <MascotAnimation />
      </div>
      {profileOpen && <ProfileSheet onClose={closeProfile} />}
    </main>
  )
}
