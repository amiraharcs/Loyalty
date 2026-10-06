import { useRef, useState } from 'react'
import intro from '../../assets/mascot/intro.mp4'
import mascot from '../../assets/mascot/mascot.gif'

const sessionKey = 'loyalty.mascot-intro'
function introPlayed() {
  try { return sessionStorage.getItem(sessionKey) === 'played' } catch { return false }
}

export default function MascotAnimation() {
  const video = useRef<HTMLVideoElement>(null)
  const [finished, setFinished] = useState(() => introPlayed() || window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  const finish = () => {
    setFinished(true)
    try { sessionStorage.setItem(sessionKey, 'played') } catch { /* Optional storage. */ }
  }
  return (
    <div className="flex justify-center pt-1 pb-2">
      <div className="mascot-frame" role="img" aria-label="GRND coffee bean mascot holding a coffee">
        {!finished ? <video ref={video} src={intro} autoPlay muted playsInline onEnded={finish} onError={finish} onLoadedData={() => { video.current?.play().catch(finish) }} className="h-full w-full object-contain" />
          : <img src={mascot} alt="" className="h-full w-full object-contain" />}
      </div>
    </div>
  )
}
