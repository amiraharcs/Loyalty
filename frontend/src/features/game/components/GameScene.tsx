import type { ReactNode } from 'react'
import shop from '../../../assets/game/shop.png'

export default function GameScene({ children, preview = false }: { children?: ReactNode; preview?: boolean }) {
  return <div className={`game-scene ${preview ? 'game-scene--preview' : 'game-scene--result'}`}>
    {preview && [{ top: '10%', left: '12%', width: '36%' }, { top: '24%', right: '9%', width: '40%' }, { top: '40%', left: '16%', width: '32%' }, { top: '54%', right: '12%', width: '38%' }].map((style, index) => <div key={index} className={`preview-platform ${index === 3 ? 'bg-accent' : 'bg-white'}`} style={style} />)}
    {children}
    <div className={`absolute inset-x-0 bottom-0 ${preview ? 'h-[40%]' : 'h-[35%]'}`}><img src={shop} alt="GRND Coffee Shop" className="h-full w-full object-contain object-bottom" /></div>
  </div>
}
