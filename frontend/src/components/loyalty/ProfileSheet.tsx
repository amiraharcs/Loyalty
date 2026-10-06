import { LogOut } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { useSession } from '../../features/session/SessionContext'
import BottomSheet from '../ui/BottomSheet'
import Button from '../ui/Button'
import PointsBalance from './PointsBalance'
import StreakCard from './StreakCard'

export default function ProfileSheet({ onClose }: { onClose: () => void }) {
  const { member, addPoints, signOut } = useSession()
  const navigate = useNavigate()
  if (!member) return null
  return (
    <BottomSheet title="Profile" onClose={onClose}>
      <div className="mb-5 flex items-center gap-4">
        <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-[#1A1A1A] text-[20px] font-semibold text-white">{member.name[0]}</div>
        <div><p className="text-[17px] font-semibold">{member.name}</p><p className="text-[13px] text-muted">{member.email}</p><p className="mt-0.5 text-[12px] font-medium text-accent">{member.tier}</p></div>
      </div>
      <div className="space-y-4">
        <PointsBalance member={member} compact />
        <StreakCard member={member} compact />
        <div className="rounded-2xl bg-white p-4">
          <p className="mb-1 text-[13px] font-semibold">Demo: Simulate a Visit</p>
          <p className="mb-3 text-[12px] text-muted">In the real app, a barista scans your QR code to add points. Tap below to simulate a visit (+100 pts).</p>
          <Button variant="accent" className="!rounded-xl !py-3 !text-[14px]" onClick={() => addPoints(100)}>Simulate Scan (+100 pts)</Button>
        </div>
        <Button variant="secondary" onClick={() => { signOut(); onClose(); navigate('/login') }}><span className="flex items-center justify-center gap-2"><LogOut size={16} />Sign out</span></Button>
      </div>
    </BottomSheet>
  )
}
