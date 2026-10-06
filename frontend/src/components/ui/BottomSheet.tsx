import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { X } from 'lucide-react'

export default function BottomSheet({ title, onClose, children }: {
  title: string; onClose: () => void; children: ReactNode
}) {
  const panel = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    panel.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
      if (event.key !== 'Tab') return
      const controls = panel.current?.querySelectorAll<HTMLElement>('button, a[href], input, [tabindex="0"]')
      if (!controls?.length) return
      const first = controls[0], last = controls[controls.length - 1]
      if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) {
        event.preventDefault(); last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault(); first.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = overflow
      document.removeEventListener('keydown', onKey)
      previousFocus?.focus()
    }
  }, [onClose])

  return (
    <div className="sheet-layer">
      <div className="sheet-backdrop" onClick={onClose} aria-hidden="true" />
      <div ref={panel} role="dialog" aria-modal="true" aria-label={title} tabIndex={-1} className="sheet-panel">
        <div className="mx-auto mb-6 h-1 w-10 rounded-full bg-[#D1D1D6]" aria-hidden="true" />
        <div className="mb-6 flex items-center justify-between">
          <h2 className="text-[20px] font-bold">{title}</h2>
          <button onClick={onClose} aria-label={`Close ${title}`} className="flex h-8 w-8 items-center justify-center rounded-full bg-[#E5E5EA]">
            <X size={16} />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
