import { useEffect, useState } from 'react'
import { readStored, writeStored } from '../../../utils/storage'
import { LIFE_RECHARGE_MS, MAX_LIVES } from '../constants'

const key = 'loyalty.game.used-lives'
export default function useLives() {
  const [usedAt, setUsedAt] = useState(() => readStored<number[]>(key, []).filter(time => Number.isFinite(time)))
  const [now, setNow] = useState(Date.now)
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer) }, [])
  const active = usedAt.filter(time => now - time < LIFE_RECHARGE_MS)
  const lives = Math.max(0, MAX_LIVES - active.length)
  const milliseconds = active.length ? Math.max(0, Math.min(...active) + LIFE_RECHARGE_MS - now) : 0
  const seconds = Math.floor(milliseconds / 1000)
  const countdown = active.length ? [Math.floor(seconds / 3600), Math.floor(seconds % 3600 / 60), seconds % 60].map(value => String(value).padStart(2, '0')).join(':') : null
  const consume = () => {
    const current = usedAt.filter(time => Date.now() - time < LIFE_RECHARGE_MS)
    if (current.length >= MAX_LIVES) return false
    const next = [...current, Date.now()]
    writeStored(key, next); setUsedAt(next); return true
  }
  return { lives, maxLives: MAX_LIVES, countdown, consume }
}
