import type { LoyaltyMember } from '../../types/loyalty'
import AnimatedNumber from '../ui/AnimatedNumber'

export default function PointsBalance({ member, compact = false }: { member: LoyaltyMember; compact?: boolean }) {
  const remaining = Math.max(0, member.nextReward - member.points)
  const progress = Math.min(100, member.points / member.nextReward * 100)
  return (
    <section aria-label="Points balance" className={compact ? 'rounded-2xl bg-white p-4' : 'surface p-6'}>
      <p className={`mb-1 font-medium tracking-widest uppercase text-muted ${compact ? 'text-[11px]' : 'text-[12px]'}`}>Points Balance</p>
      <p className={`font-bold leading-none ${compact ? 'text-[36px]' : 'text-[56px]'}`}><AnimatedNumber value={member.points} /></p>
      <p className={`text-muted ${compact ? 'mt-1 text-[12px]' : 'mt-2 text-[13px]'}`}>
        {remaining ? `${remaining.toLocaleString()} points until your next reward` : 'Reward ready to claim!'}
      </p>
      <div role="progressbar" aria-label="Progress to next reward" aria-valuemin={0} aria-valuemax={member.nextReward} aria-valuenow={Math.min(member.points, member.nextReward)} className={`${compact ? 'mt-3' : 'mt-4'} h-1.5 overflow-hidden rounded-full bg-[#F0EFEA]`}>
        <div className="h-full rounded-full bg-[#1A1A1A] transition-[width] duration-500" style={{ width: `${progress}%` }} />
      </div>
    </section>
  )
}
