import { Coffee } from 'lucide-react'

export default function BrandLogo({ light = false }: { light?: boolean }) {
  return (
    <div className={`flex items-center gap-2 ${light ? 'text-white' : ''}`}>
      <Coffee size={20} strokeWidth={2.5} aria-hidden="true" />
      <span className="text-[15px] font-bold tracking-widest uppercase">GRND</span>
    </div>
  )
}
