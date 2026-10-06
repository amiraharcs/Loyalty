interface TiltPermissionEvent { requestPermission?: () => Promise<string> }

export async function requestTiltPermission() {
  const orientation = window.DeviceOrientationEvent as unknown as TiltPermissionEvent | undefined
  if (typeof orientation?.requestPermission === 'function') {
    try { await orientation.requestPermission() } catch { /* Touch and keyboard remain available. */ }
  }
}

export function attachInput(canvas: HTMLCanvasElement) {
  const state = { left: false, right: false, touch: 0, tilt: 0 }
  const keyboard = (event: KeyboardEvent, pressed: boolean) => {
    if (['ArrowLeft', 'ArrowRight', 'a', 'd'].includes(event.key)) event.preventDefault()
    if (event.key === 'ArrowLeft' || event.key === 'a') state.left = pressed
    if (event.key === 'ArrowRight' || event.key === 'd') state.right = pressed
  }
  const down = (event: KeyboardEvent) => keyboard(event, true)
  const up = (event: KeyboardEvent) => keyboard(event, false)
  const touch = (event: TouchEvent) => {
    event.preventDefault()
    const rect = canvas.getBoundingClientRect()
    state.touch = Math.max(-1, Math.min(1, (event.touches[0].clientX - rect.left - rect.width / 2) / (rect.width / 2)))
  }
  const release = () => { state.left = false; state.right = false; state.touch = 0; state.tilt = 0 }
  const tilt = (event: DeviceOrientationEvent) => { state.tilt = event.gamma ?? 0 }
  window.addEventListener('keydown', down)
  window.addEventListener('keyup', up)
  window.addEventListener('blur', release)
  window.addEventListener('deviceorientation', tilt)
  canvas.addEventListener('touchstart', touch, { passive: false })
  canvas.addEventListener('touchmove', touch, { passive: false })
  canvas.addEventListener('touchend', release)
  canvas.addEventListener('touchcancel', release)
  return {
    state,
    cleanup() {
      window.removeEventListener('keydown', down); window.removeEventListener('keyup', up)
      window.removeEventListener('blur', release); window.removeEventListener('deviceorientation', tilt)
      canvas.removeEventListener('touchstart', touch); canvas.removeEventListener('touchmove', touch)
      canvas.removeEventListener('touchend', release); canvas.removeEventListener('touchcancel', release)
    },
  }
}
