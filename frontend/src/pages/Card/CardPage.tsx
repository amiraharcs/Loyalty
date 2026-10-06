import { useCallback, useState } from 'react'
import { Wifi, Wallet } from 'lucide-react'
import BrandLogo from '../../components/layout/BrandLogo'
import AnimatedNumber from '../../components/ui/AnimatedNumber'
import BottomSheet from '../../components/ui/BottomSheet'
import Button from '../../components/ui/Button'
import { useSession } from '../../features/session/SessionContext'
import QrCode from './QrCode'

export default function CardPage() {
  const { member } = useSession()
  const [flipped, setFlipped] = useState(false)
  const [walletOpen, setWalletOpen] = useState(false)
  const [spotlight, setSpotlight] = useState<{ x: number; y: number } | null>(null)
  const closeWallet = useCallback(() => setWalletOpen(false), [])
  if (!member) return null
  return (
    <main className="page">
      <header className="flex justify-center px-6 pt-14 pb-6"><BrandLogo /></header>
      <div className="space-y-8 px-6">
        <button aria-label={flipped ? 'Flip to membership card' : 'Flip to QR code'} aria-pressed={flipped} onClick={() => setFlipped(value => !value)} className="block aspect-[1.6/1] w-full text-left" style={{ perspective: 1200 }} onPointerMove={event => {
          const rect = event.currentTarget.getBoundingClientRect()
          setSpotlight({ x: event.clientX - rect.left, y: event.clientY - rect.top })
        }} onPointerLeave={() => setSpotlight(null)}>
          <div className="loyalty-card" style={{ transform: `rotateY(${flipped ? 180 : 0}deg)` }}>
            <div className="loyalty-card-face flex flex-col justify-between overflow-hidden bg-[#1C1C1E] p-6 text-white" aria-hidden={flipped}>
              <div className="flex items-center justify-between"><BrandLogo light /><span className="text-[11px] tracking-widest uppercase text-muted">{member.tier}</span></div>
              <div><p className="mb-1 text-[11px] tracking-widest uppercase text-muted">Balance</p><p className="text-[42px] leading-none font-bold"><AnimatedNumber value={member.points} /><span className="ml-1 text-[16px] font-normal text-muted">pts</span></p><p className="mt-2 text-[16px] font-medium">{member.name}</p></div>
              <div className="pointer-events-none absolute inset-0" style={{ background: spotlight ? `radial-gradient(circle 200px at ${spotlight.x}px ${spotlight.y}px, rgba(244,162,97,.10), transparent)` : undefined }} />
            </div>
            <div className="loyalty-card-face flex flex-col items-center justify-center gap-4 bg-white p-6" aria-hidden={!flipped} style={{ transform: 'rotateY(180deg)' }}>
              <QrCode /><div className="text-center"><p className="text-[13px] font-semibold">{member.name}</p><p className="mt-0.5 text-[11px] tracking-widest uppercase text-muted">Scan to collect points</p></div>
            </div>
          </div>
        </button>
        <div className="flex flex-col items-center gap-4 py-6">
          <div className="relative flex h-24 w-24 items-center justify-center">
            {[0, 1, 2].map(index => <span key={index} className="scan-ring" aria-hidden="true" style={{ animationDelay: `${index * .55}s` }} />)}
            <div className="relative z-10 flex h-24 w-24 items-center justify-center rounded-full bg-white shadow-sm"><Wifi size={40} strokeWidth={1.5} className="rotate-90" aria-hidden="true" /></div>
          </div>
          <div className="text-center"><p className="text-[16px] font-semibold">Ready to Scan</p><p className="mt-1 text-[13px] text-muted">Hold near reader to pay or collect points</p></div>
        </div>
        <div className="space-y-3"><Button shimmer onClick={() => setFlipped(value => !value)}>{flipped ? 'Show Card' : 'Show QR Code'}</Button><Button variant="secondary" onClick={() => setWalletOpen(true)}>Add to Wallet</Button></div>
      </div>
      {walletOpen && <BottomSheet title="Add to Wallet" onClose={closeWallet}>
        <div className="flex flex-col items-center gap-4 py-4 text-center"><div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-[#1A1A1A] text-white"><Wallet size={28} /></div><div><p className="mb-2 text-[18px] font-bold">Coming Soon</p><p className="max-w-[280px] text-[14px] leading-relaxed text-muted">Apple Wallet and Google Wallet support is on its way. Your GRND loyalty card will live right in your wallet — no app needed to scan.</p></div></div>
        <Button className="mt-6" onClick={closeWallet}>Got it</Button>
      </BottomSheet>}
    </main>
  )
}
