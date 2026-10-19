import { useNavigate } from 'react-router-dom'
import Button from '../../../components/ui/Button'
import type { GameResult } from '../types'
import GameScene from './GameScene'
import LivesIndicator from './LivesIndicator'

export default function GameOverScreen({ result, lives, maxLives, countdown, onReplay, children, replayDisabled = false }: { result: GameResult; lives: number; maxLives: number; countdown: string | null; onReplay: () => void; children?: import('react').ReactNode; replayDisabled?: boolean }) {
  const navigate = useNavigate()
  return <div className="game-over">
    <h1 className="shrink-0 text-center text-[48px] leading-none font-black tracking-tight uppercase">Game Over</h1>
    <GameScene>
      {result.newBest && <div className="best-ribbon">New Personal Best</div>}
      <div className="absolute top-5 left-1/2 z-10 flex -translate-x-1/2 flex-col items-center rounded-2xl bg-[#1A1A1A] px-8 py-3 text-white"><span className="text-[40px] leading-none font-black">{result.score.toLocaleString()}</span>{result.best !== null && result.best > result.score && <span className="mt-0.5 text-[11px] text-muted">Best <strong className="text-white">{result.best.toLocaleString()}</strong></span>}</div>
      <p className="absolute top-[33%] left-1/2 z-10 -translate-x-1/2 -translate-y-1/2 text-[22px] font-bold whitespace-nowrap">Your Run</p>
      <div className="absolute top-[52%] left-1/2 z-10 -translate-x-1/2 -translate-y-1/2 whitespace-nowrap"><LivesIndicator lives={lives} maxLives={maxLives} countdown={countdown} large /></div>
    </GameScene>
    <div className="shrink-0 space-y-2.5">{children}<Button shimmer disabled={!lives || replayDisabled} onClick={onReplay}>{lives ? 'Play Again' : 'No lives left'}</Button><Button variant="secondary" className="!border-2 !border-[#1A1A1A] !bg-transparent !font-bold" onClick={() => navigate('/leaderboard')}>View Leaderboard</Button></div>
  </div>
}
