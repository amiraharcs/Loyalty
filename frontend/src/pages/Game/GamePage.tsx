import { useEffect, useRef, useState } from 'react'
import { Trophy } from 'lucide-react'
import { Link } from 'react-router-dom'
import Button from '../../components/ui/Button'
import GameHUD from '../../features/game/components/GameHUD'
import GameScene from '../../features/game/components/GameScene'
import GameOverScreen from '../../features/game/components/GameOverScreen'
import LivesIndicator from '../../features/game/components/LivesIndicator'
import { startEngine } from '../../features/game/engine'
import useGameLifecycle from '../../features/game/hooks/useGameLifecycle'
import { requestTiltPermission } from '../../features/game/input'

export default function GamePage() {
  const game = useGameLifecycle()
  const [score, setScore] = useState(0)
  const [intentStarting, setIntentStarting] = useState(false)
  const canvas = useRef<HTMLCanvasElement>(null)
  const startLock = useRef(false)
  const alive = useRef(true)
  const end = useRef(game.finish)
  const best = useRef(game.best)
  end.current = game.finish; best.current = game.best
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const sessionId = game.session?.sessionId
  useEffect(() => {
    if (game.phase !== 'playing' || !canvas.current) return
    setScore(0)
    return startEngine(canvas.current, best.current ?? 0, setScore, value => end.current(value))
  }, [game.phase, sessionId])
  const start = async () => {
    if (startLock.current) return
    startLock.current = true; setIntentStarting(true)
    try { await requestTiltPermission(); await game.begin() }
    finally { startLock.current = false; if (alive.current) setIntentStarting(false) }
  }
  const data = game.lives.data
  const saving = game.submissionStatus === 'pending'
  const feedback = <div className="space-y-2 text-center text-[13px]" aria-live="polite">
    {saving && <p role="status">Saving score…</p>}
    {game.submissionStatus === 'accepted' && <p role="status">Score saved</p>}
    {game.accepted && game.finalScore !== game.accepted.score && <p>This run: {game.finalScore} · Saved score: {game.accepted.score}</p>}
    {game.error && <p role="alert">{game.error}</p>}
    {game.retrySeconds > 0 && <p role="status">Retry in {game.retrySeconds}s</p>}
    {game.submissionStatus === 'failed' && <Button variant="secondary" disabled={saving || game.retrySeconds > 0} onClick={() => void game.retryScore()}>Retry saving score</Button>}
    {game.summaryError && <p>{game.summaryError} <button className="underline" onClick={() => void game.refreshSummary()}>Retry best</button></p>}
  </div>
  return <main style={{ minHeight: '100svh' }}>
    {game.phase === 'idle' && <div className="game-preview">
      <header className="flex shrink-0 items-center justify-between px-5 pt-10 pb-1"><span className="flex items-center gap-1.5 text-[13px] font-semibold"><Trophy size={14} className="text-accent" />GRND Jump</span><Link to="/leaderboard" className="text-[13px] text-muted underline underline-offset-2">Leaderboard preview</Link></header>
      <div className="shrink-0 px-5 pt-3 pb-2 text-center"><h1 className="text-[42px] leading-none font-black tracking-tight uppercase">GRND Jump</h1><p className="mt-1.5 text-[13px] font-medium">Keep jumping. Beat your personal best.</p></div>
      <GameScene preview>{game.best !== null && <div className="absolute top-4 left-1/2 z-10 -translate-x-1/2 rounded-full bg-[#1A1A1A] px-5 py-2 text-[13px] whitespace-nowrap text-white">Personal Best: <strong>{game.best.toLocaleString()}</strong></div>}</GameScene>
      <div className="shrink-0 space-y-3 px-5 pt-3 pb-4">
        {game.lives.status === 'loading' && <p role="status" className="text-center text-[13px]">Loading lives…</p>}
        {data && <LivesIndicator lives={data.availableLives} maxLives={data.maxLives} countdown={game.lives.countdown} />}
        {game.lives.status === 'error' && <div className="text-center text-[13px]"><p role="alert">{game.lives.error}</p><button className="underline" onClick={() => void game.lives.refresh()}>Retry lives</button></div>}
        {feedback}
        <Button shimmer disabled={!game.canStart || intentStarting} onClick={() => void start()}>{intentStarting || game.startStatus === 'pending' ? 'Starting…' : game.startStatus === 'failed' ? 'Retry start' : data?.availableLives === 0 ? 'No lives left' : 'Start Game'}</Button>
      </div>
    </div>}
    {game.phase === 'playing' && <><canvas ref={canvas} className="game-canvas" aria-label="GRND Jump game. Use arrow keys or A and D, touch either side, or tilt your phone to steer." /><GameHUD score={score} /></>}
    {game.phase === 'gameover' && <GameOverScreen
      result={{ score: game.accepted?.score ?? game.finalScore ?? score, best: game.best, newBest: game.newBest }}
      lives={data?.availableLives ?? 0} maxLives={data?.maxLives ?? 0} countdown={game.lives.countdown}
      replayDisabled={saving || game.submissionStatus === 'failed' || game.lives.status !== 'ready'}
      onReplay={game.returnToPreview}>{feedback}</GameOverScreen>}
  </main>
}
