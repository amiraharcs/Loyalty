import { competitors } from '../../leaderboard/rankings'

export default function GameHUD({ score }: { score: number }) {
  const target = [...competitors].reverse().find(entry => entry.score > score)
  return <div className="pointer-events-none fixed inset-x-0 top-12 z-[60] h-11">
    <div className="absolute left-1/2 -translate-x-1/2 rounded-full bg-[#1A1A1A] px-6 py-2.5 text-[15px] font-bold text-white">SCORE: {score.toLocaleString()}</div>
    <div className="absolute top-0 right-4 flex h-full items-center">{target ? <div className="flex items-center gap-1.5 rounded-xl bg-accent px-2.5 py-1.5 text-white"><span className="flex h-6 w-6 items-center justify-center rounded-full bg-black/20 text-[11px] font-black">{target.name[0]}</span><div><p className="text-[11px] leading-none font-black">#{target.rank} {target.name}</p><p className="mt-0.5 text-[9px] leading-tight font-semibold text-white/80">{(target.score - score).toLocaleString()} to go</p></div></div> : <div className="rounded-xl bg-[#1A1A1A] px-2.5 py-1.5 text-[12px] font-black text-white">🏆 #1!</div>}</div>
  </div>
}
