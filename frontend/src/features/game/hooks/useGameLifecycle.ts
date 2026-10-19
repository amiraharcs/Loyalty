import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../../api/client'
import * as api from '../../../api/game'
import type { AcceptedScore, GameSession } from '../../../api/game'
import { gameErrorMessage } from '../apiErrors'
import useLives from './useLives'

type RequestStatus = 'idle' | 'pending' | 'failed' | 'accepted' | 'rejected'
interface StartAttempt { key: string; tries: number }
interface ScoreAttempt { sessionId: string; score: number; key: string }

/** Network work occurs only at lifecycle boundaries, never in the engine's animation loop. */
export default function useGameLifecycle() {
  const lives = useLives()
  const [best, setBest] = useState<number | null>(null)
  const [previousBest, setPreviousBest] = useState<number | null>(null)
  const [summaryError, setSummaryError] = useState('')
  const [phase, setPhase] = useState<'idle' | 'playing' | 'gameover'>('idle')
  const [session, setSession] = useState<GameSession | null>(null)
  const [startStatus, setStartStatus] = useState<RequestStatus>('idle')
  const [submissionStatus, setSubmissionStatus] = useState<RequestStatus>('idle')
  const [finalScore, setFinalScore] = useState<number | null>(null)
  const [accepted, setAccepted] = useState<AcceptedScore | null>(null)
  const [error, setError] = useState('')
  const [activeConflict, setActiveConflict] = useState(false)
  const [retryAt, setRetryAt] = useState(0)
  const [retrySeconds, setRetrySeconds] = useState(0)
  const mounted = useRef(false)
  const startAttempt = useRef<StartAttempt | null>(null)
  const scoreAttempt = useRef<ScoreAttempt | null>(null)
  const active = useRef<GameSession | null>(null)
  const finished = useRef(false)
  const starting = useRef(false)
  const saving = useRef(false)
  const summaryRevision = useRef(0)
  const refreshSummary = useCallback(async () => {
    const revision = ++summaryRevision.current
    try {
      const value = await api.getGameSummary()
      if (mounted.current && revision === summaryRevision.current) { setBest(value.personalBest); setSummaryError('') }
    } catch {
      if (mounted.current && revision === summaryRevision.current) setSummaryError('Personal best unavailable.')
    }
  }, [])
  useEffect(() => {
    mounted.current = true
    void refreshSummary()
    return () => { mounted.current = false }
  }, [refreshSummary])
  useEffect(() => {
    const tick = () => setRetrySeconds(Math.max(0, Math.ceil((retryAt - performance.now()) / 1000)))
    tick()
    const timer = setInterval(tick, 500)
    return () => clearInterval(timer)
  }, [retryAt])
  const report = useCallback((cause: unknown) => {
    setError(gameErrorMessage(cause))
    if (cause instanceof ApiError && cause.status === 429) setRetryAt(performance.now() + Math.max(1, cause.retryAfter) * 1000)
  }, [])
  const updateLives = lives.apply
  const refreshLives = lives.refresh
  const applySession = useCallback((value: GameSession) => {
    if (!lives.data) throw new Error('Game capacity is unavailable')
    active.current = value; setSession(value)
    updateLives({ availableLives: value.remainingLives, maxLives: lives.data.maxLives, nextRechargeAt: value.nextRechargeAt, serverTime: value.serverTime })
  }, [lives.data, updateLives])
  const begin = useCallback(async () => {
    if (!mounted.current || activeConflict || starting.current || saving.current || performance.now() < retryAt) return
    if (!startAttempt.current && (lives.status !== 'ready' || !lives.data?.availableLives)) return
    starting.current = true; setStartStatus('pending'); setError('')
    startAttempt.current ??= { key: crypto.randomUUID(), tries: 0 }
    const attempt = startAttempt.current
    attempt.tries++
    try {
      let value = await api.startGameSession(attempt.key)
      // An exact replay can contain an old ACTIVE snapshot; validate it before restarting the loop.
      if (attempt.tries > 1) value = await api.getGameSession(value.sessionId)
      if (!mounted.current) return
      applySession(value)
      if (value.status !== 'ACTIVE' || value.engineVersion !== 'v1') {
        setStartStatus('rejected')
        if (value.status === 'ACTIVE') setActiveConflict(true)
        setError(value.status === 'COMPLETED' ? 'This run has already finished.' : value.status === 'EXPIRED' ? 'This run expired. Please start a new game.' : 'This game version is unavailable. Please try again later.')
        startAttempt.current = null
        return
      }
      startAttempt.current = null; scoreAttempt.current = null; finished.current = false
      setPreviousBest(best); setFinalScore(null); setAccepted(null); setSubmissionStatus('idle'); setStartStatus('idle'); setPhase('playing')
    } catch (cause) {
      if (!mounted.current) return
      // Only ambiguous/transient failures retain the original operation for a safe retry.
      const retryable = !(cause instanceof ApiError) || cause.status >= 500 || cause.status === 429 || cause.status === 403
      setStartStatus(retryable ? 'failed' : 'rejected'); report(cause)
      if (cause instanceof ApiError && cause.code === 'GAME_SESSION_ACTIVE') setActiveConflict(true)
      if (!retryable) startAttempt.current = null
      if (cause instanceof ApiError && (cause.code === 'NO_GAME_LIVES' || cause.code === 'GAME_SESSION_ACTIVE')) {
        await refreshLives()
      }
    } finally { starting.current = false }
  }, [lives.status, lives.data, retryAt, applySession, report, refreshLives, best, activeConflict])
  const save = useCallback(async () => {
    const attempt = scoreAttempt.current
    if (!attempt || saving.current || performance.now() < retryAt) return
    saving.current = true; setSubmissionStatus('pending'); setError('')
    try {
      const value = await api.submitGameScore(attempt.sessionId, attempt.score, attempt.key)
      if (!mounted.current) return
      setAccepted(value); setSubmissionStatus('accepted')
      void refreshSummary()
    } catch (cause) {
      if (!mounted.current) return
      report(cause)
      const terminal = cause instanceof ApiError && ['SCORE_IMPLAUSIBLE', 'SESSION_EXPIRED', 'SCORE_ALREADY_SUBMITTED', 'IDEMPOTENCY_KEY_REUSED'].includes(cause.code)
      setSubmissionStatus(terminal ? 'rejected' : 'failed')
      if (cause instanceof ApiError && cause.code === 'SCORE_ALREADY_SUBMITTED') {
        try {
          const value = await api.getAcceptedScore(attempt.sessionId)
          if (mounted.current) { setAccepted(value); setSubmissionStatus('accepted'); void refreshSummary() }
        } catch { /* Keep the safe conflict state. Never replace the stored score with this run. */ }
      }
      if (cause instanceof ApiError && cause.code === 'SESSION_EXPIRED') await refreshLives()
    } finally { saving.current = false }
  }, [retryAt, report, refreshSummary, refreshLives])
  const finish = useCallback((value: number) => {
    if (!active.current || finished.current || !mounted.current) return
    finished.current = true
    // Freeze exactly one result and key, even if the engine repeats its end callback.
    if (!Number.isSafeInteger(value) || value < 0) {
      setPhase('gameover'); setSubmissionStatus('rejected'); setError("We couldn't verify this run. Please start a new game."); return
    }
    scoreAttempt.current = { sessionId: active.current.sessionId, score: value, key: crypto.randomUUID() }
    setFinalScore(value); setPhase('gameover')
    void save()
  }, [save])
  const returnToPreview = useCallback(() => {
    if (saving.current || submissionStatus === 'failed') return
    active.current = null; setSession(null); setPhase('idle'); setError('')
  }, [submissionStatus])
  return {
    lives, best, summaryError, refreshSummary, phase, session, startStatus, submissionStatus, finalScore, accepted,
    error, retrySeconds, begin, finish, retryScore: save, returnToPreview,
    newBest: accepted !== null && best === accepted.score && previousBest !== null && accepted.score > previousBest,
    canStart: !activeConflict && (startAttempt.current !== null || (lives.status === 'ready' && !!lives.data?.availableLives)) && startStatus !== 'pending' && retrySeconds === 0,
  }
}
