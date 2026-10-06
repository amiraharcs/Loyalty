import { useCallback, useEffect, useRef, useState } from 'react'
import { Trophy } from 'lucide-react'
import { Link } from 'react-router-dom'
import Button from '../../components/ui/Button'
import GameHUD from '../../features/game/components/GameHUD'
import GameScene from '../../features/game/components/GameScene'
import GameOverScreen from '../../features/game/components/GameOverScreen'
import LivesIndicator from '../../features/game/components/LivesIndicator'
import { startEngine } from '../../features/game/engine'
import useLives from '../../features/game/hooks/useLives'
import { requestTiltPermission } from '../../features/game/input'
import type { GamePhase, GameResult } from '../../features/game/types'
import { personalBest, rankings, saveScore } from '../../features/leaderboard/rankings'
import { useSession } from '../../features/session/SessionContext'

export default function GamePage() {
  const { member } = useSession()
  const [phase, setPhase] = useState<GamePhase>('idle')
  const [score, setScore] = useState(0)
  const [best, setBest] = useState(personalBest)
  const [result, setResult] = useState<GameResult | null>(null)
  const [starting, setStarting] = useState(false)
  const canvas = useRef<HTMLCanvasElement>(null)
  const startLock = useRef(false)
  const { lives, maxLives, countdown, consume } = useLives()
  const finish = useCallback((finalScore: number) => {
    if (!member) return
    saveScore(member.email, finalScore)
    const rank = rankings(member.email, member.name).find(entry => entry.current)?.rank ?? 9
    setResult({ score: finalScore, best: Math.max(best, finalScore), newBest: finalScore > best, rank })
    setBest(Math.max(best, finalScore)); setPhase('gameover')
  }, [member, best])
  useEffect(() => {
    if (phase !== 'playing' || !canvas.current) return
    return startEngine(canvas.current, best, setScore, finish)
  }, [phase, best, finish])
  const start = async () => {
    if (startLock.current || !lives) return
    startLock.current = true; setStarting(true)
    await requestTiltPermission()
    if (consume()) { setScore(0); setPhase('playing') }
    startLock.current = false; setStarting(false)
  }
  return <main style={{ minHeight: '100svh' }}>
    {phase === 'idle' && <div className="game-preview">
      <header className="flex shrink-0 items-center justify-between px-5 pt-10 pb-1"><span className="flex items-center gap-1.5 text-[13px] font-semibold"><Trophy size={14} className="text-accent" />Free Coffee</span><Link to="/leaderboard" className="text-[13px] text-muted underline underline-offset-2">Leaderboard</Link></header>
      <div className="shrink-0 px-5 pt-3 pb-2 text-center"><h1 className="text-[42px] leading-none font-black tracking-tight uppercase">GRND Jump</h1><p className="mt-1.5 text-[13px] font-medium">Rank in the Top 3 this week to win a FREE coffee!</p></div>
      <GameScene preview>{best > 0 && <div className="absolute top-4 left-1/2 z-10 -translate-x-1/2 rounded-full bg-[#1A1A1A] px-5 py-2 text-[13px] whitespace-nowrap text-white">Personal Best: <strong>{best.toLocaleString()}</strong></div>}</GameScene>
      <div className="shrink-0 space-y-3 px-5 pt-3 pb-4"><LivesIndicator lives={lives} maxLives={maxLives} countdown={countdown} /><Button shimmer disabled={!lives || starting} onClick={start}>{lives ? 'Start Game' : 'No lives left'}</Button></div>
    </div>}
    {phase === 'playing' && <><canvas ref={canvas} className="game-canvas" aria-label="GRND Jump game. Use arrow keys or A and D, touch either side, or tilt your phone to steer." /><GameHUD score={score} /></>}
    {phase === 'gameover' && result && <GameOverScreen result={result} lives={lives} maxLives={maxLives} countdown={countdown} onReplay={() => setPhase('idle')} />}
  </main>
}
