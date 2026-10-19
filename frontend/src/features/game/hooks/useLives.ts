import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../../api/client'
import { getGameLives } from '../../../api/game'
import type { GameLives } from '../../../api/game'
import { gameErrorMessage } from '../apiErrors'

export default function useLives() {
  const [data, setData] = useState<GameLives | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState('')
  const [countdown, setCountdown] = useState<string | null>(null)
  const mounted = useRef(false)
  const observedAt = useRef(0)
  const request = useRef<Promise<void> | null>(null)
  const revision = useRef(0)
  const retryAt = useRef(0)
  const apply = useCallback((value: GameLives) => {
    revision.current++
    if (!mounted.current) return
    observedAt.current = performance.now()
    setData(value); setStatus('ready'); setError('')
  }, [])
  const refresh = useCallback((): Promise<void> => {
    if (request.current) return request.current
    if (performance.now() < retryAt.current) return Promise.resolve()
    const started = revision.current
    if (mounted.current) setStatus('loading')
    const pending = getGameLives().then(value => {
      if (started === revision.current) apply(value) // A newer start response wins over an older GET.
    }).catch((cause: unknown) => {
      if (cause instanceof ApiError && cause.status === 429) retryAt.current = performance.now() + Math.max(1, cause.retryAfter) * 1000
      if (mounted.current && started === revision.current) { setStatus('error'); setError(gameErrorMessage(cause)) }
    }).finally(() => { request.current = null })
    request.current = pending
    return pending
  }, [apply])
  useEffect(() => {
    mounted.current = true
    void refresh()
    return () => { mounted.current = false }
  }, [refresh])
  useEffect(() => {
    if (!data?.nextRechargeAt) { setCountdown(null); return }
    let requested = false
    const tick = () => {
      const remaining = Math.max(0, Date.parse(data.nextRechargeAt!) - Date.parse(data.serverTime) - (performance.now() - observedAt.current))
      const seconds = Math.ceil(remaining / 1000)
      setCountdown([Math.floor(seconds / 3600), Math.floor(seconds % 3600 / 60), seconds % 60].map(value => String(value).padStart(2, '0')).join(':'))
      if (remaining === 0 && !requested) { requested = true; void refresh() }
    }
    tick()
    const timer = setInterval(tick, 1000)
    return () => clearInterval(timer)
  }, [data, refresh])
  return { data, status, error, countdown, refresh, apply }
}
