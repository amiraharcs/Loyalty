import type { LoyaltyMember } from '../../types/loyalty'
import CoffeeBean from './CoffeeBean'

export default function StreakCard({ member, compact = false }: { member: LoyaltyMember; compact?: boolean }) {
  return (
    <section aria-label={`${member.streak} of ${member.maxStreak} visits`} className={compact ? 'rounded-2xl bg-white p-4' : 'surface p-6'}>
      <div className={`flex items-center justify-between ${compact ? 'mb-3' : 'mb-4'}`}>
        <h2 className="text-[14px] font-semibold">10-Day Streak</h2>
        <p className="text-[13px] text-muted">{member.streak}/{member.maxStreak}</p>
      </div>
      <div className={`flex items-end ${compact ? 'gap-1' : 'gap-1.5'}`}>
        {Array.from({ length: member.maxStreak }, (_, index) => <div key={index} className="aspect-[4/5] min-w-0 flex-1"><CoffeeBean filled={index < member.streak} /></div>)}
      </div>
      {compact ? (
        <div className="mt-3 rounded-xl bg-[#F5F4EF] p-3">
          <p className="mb-1 text-[12px] font-semibold">How it works</p>
          <p className="text-[12px] leading-relaxed text-muted">Visit GRND once a day to fill a coffee bean. Fill all 10 beans in 10 consecutive days to earn a <span className="font-medium text-[#1A1A1A]">free coffee</span>. Miss a day and your streak resets.</p>
        </div>
      ) : <p className="mt-3 text-[12px] text-muted">{member.maxStreak - member.streak} more visits to earn a free coffee</p>}
    </section>
  )
}
