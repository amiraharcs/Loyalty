import { useEffect, useRef, useState } from 'react'

export default function AnimatedNumber({ value, className = '' }: { value: number; className?: string }) {
  const [display, setDisplay] = useState(value)
  const previous = useRef(0)

  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setDisplay(value)
      previous.current = value
      return
    }
    const from = previous.current
    let frame = 0
    let started: number | undefined
    const tick = (time: number) => {
      started ??= time
      const progress = Math.min((time - started) / 1200, 1)
      const next = Math.round(from + (value - from) * (1 - (1 - progress) ** 4))
      setDisplay(next)
      previous.current = next
      if (progress < 1) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [value])

  return <span className={`tabular-nums ${className}`}>{display.toLocaleString('en-US')}</span>
}
