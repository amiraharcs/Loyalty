import filled from '../../../assets/game/cup-filled.png'
import empty from '../../../assets/game/cup-empty.png'

export default function LivesIndicator({ lives, maxLives, countdown, large = false }: { lives: number; maxLives: number; countdown: string | null; large?: boolean }) {
  return <div className="flex flex-col items-center gap-1.5">
    <div className="flex items-center justify-center gap-3" aria-label={`${lives} of ${maxLives} lives remaining`}>{Array.from({ length: maxLives }, (_, index) => <img key={index} src={index < lives ? filled : empty} alt="" className={`${large ? 'w-14' : 'w-10'} h-auto`} />)}</div>
    {countdown && <p className="text-[13px] text-muted">Next life in: <span className="font-bold text-[#1A1A1A] tabular-nums">{countdown}</span></p>}
  </div>
}
