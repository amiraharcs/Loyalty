import { useState } from 'react'
import { ChevronLeft, Trophy, Users } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { useSession } from '../../features/session/SessionContext'
import { prizes, rankings } from '../../features/leaderboard/rankings'
import type { Ranking } from '../../features/leaderboard/rankings'

function Podium({ entries }: { entries: Ranking[] }) {
  return <div className="mb-4 flex items-end justify-center gap-3 px-6">
    {[1, 0, 2].map(index => {
      const entry = entries[index]
      return <div key={entry.id} className="flex min-w-0 flex-1 flex-col items-center">
        {index === 0 && <Trophy size={14} className="mb-1 text-accent" />}
        <div className={`mb-1 flex items-center justify-center rounded-full font-bold ${index === 0 ? 'h-12 w-12 text-[16px]' : 'h-10 w-10 text-[14px]'} ${entry.current ? 'bg-accent text-white' : index === 0 ? 'bg-[#1A1A1A] text-white' : 'border border-[#E5E5EA] bg-white'}`}>{entry.name[0]}</div>
        <p className="max-w-[70px] truncate text-center text-[11px] font-semibold">{entry.name}</p><p className="text-[10px] text-muted">{entry.score.toLocaleString()}</p>
        {index === 0 && <p className="mt-0.5 text-[9px] font-semibold text-accent">Defending 🏆</p>}
        <div className={`mt-1 flex w-full items-center justify-center rounded-t-lg font-bold ${index === 0 ? 'h-16 bg-[#1A1A1A] text-[18px] text-white' : index === 1 ? 'h-12 bg-[#E5E5EA] text-[16px] text-muted' : 'h-8 bg-[#D1D1D6] text-[14px] text-white'}`}>{index + 1}</div>
      </div>
    })}
  </div>
}

export default function LeaderboardPage() {
  const { member } = useSession()
  const navigate = useNavigate()
  const [tab, setTab] = useState<'Weekly' | 'Friends'>('Weekly')
  if (!member) return null
  const entries = rankings(member.email, member.name.split(' ')[0], tab === 'Friends')
  return <main className="page">
    <header className="px-6 pt-14 pb-4">
      <div className="mb-5 flex items-center gap-3"><button aria-label="Back to game" onClick={() => navigate('/game')} className="text-muted"><ChevronLeft size={24} /></button><Trophy size={18} className="text-accent" /><h1 className="text-[18px] font-bold">Leaderboard</h1></div>
      <div className="mb-4 flex items-center justify-between rounded-2xl bg-[#1A1A1A] px-4 py-3"><div><p className="text-[11px] font-medium tracking-widest uppercase text-muted">Weekly prize — #1</p><p className="mt-0.5 text-[16px] font-bold text-white">Free Coffee</p></div><Trophy size={26} className="text-accent" /></div>
      <div className="flex gap-1 rounded-2xl bg-white p-1" role="tablist" aria-label="Leaderboard category">{(['Weekly', 'Friends'] as const).map(value => <button key={value} role="tab" aria-selected={tab === value} aria-controls="rankings" onClick={() => setTab(value)} className={`flex flex-1 items-center justify-center gap-1.5 rounded-xl py-2.5 text-[13px] font-semibold transition-colors ${tab === value ? 'bg-[#1A1A1A] text-white' : 'text-muted'}`}>{value === 'Weekly' ? <Trophy size={12} /> : <Users size={12} />}{value}</button>)}</div>
    </header>
    <div id="rankings" role="tabpanel" aria-label={tab}>
      {tab === 'Weekly' && <Podium entries={entries.slice(0, 3)} />}
      <div className="space-y-2 px-6">{(tab === 'Weekly' ? entries.slice(3) : entries).map(entry => <div key={entry.id} className={`flex items-center gap-3 rounded-2xl p-4 ${entry.current ? 'bg-accent text-white' : 'bg-white'}`}>
        <span className={`w-7 shrink-0 text-center text-[13px] font-bold ${entry.current ? '' : 'text-muted'}`}>{entry.rank}</span>
        <div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-[13px] font-bold ${entry.current ? 'bg-white text-accent' : 'bg-[#F5F4EF]'}`}>{entry.name[0]}</div>
        <div className="min-w-0 flex-1"><p className="truncate text-[14px] font-semibold">{entry.name}{entry.current && ' (You)'}</p>{prizes[entry.rank] && <p className={`text-[11px] ${entry.current ? 'text-white/80' : 'text-accent'}`}>{prizes[entry.rank]}</p>}</div>
        <div className="shrink-0 text-right"><p className="text-[14px] font-bold">{entry.score ? entry.score.toLocaleString() : '—'}</p>{entry.current && entry.score < 9200 && <p className="text-[10px] text-white/70">{(9200 - entry.score).toLocaleString()} behind #1</p>}</div>
      </div>)}</div>
    </div>
  </main>
}
