import { BOUNCE_SPEED, CAMERA_LINE, GRAVITY, MAX_HORIZONTAL_SPEED, PLAYER_HEIGHT, PLAYER_WIDTH, SUGAR_RUSH_FRAMES } from './constants'
import { attachInput } from './input'
import { landingPlatform, wrapsHorizontally } from './physics'
import { createRenderer } from './renderer'
import type { Platform, Player, SugarCube } from './types'

export function startEngine(canvas: HTMLCanvasElement, best: number, onScore: (score: number) => void, onEnd: (score: number) => void) {
  const context = canvas.getContext('2d')!
  let width = window.innerWidth, height = window.innerHeight
  let scroll = 0, score = 0, rush = 0, frame = 0, paused = false, stopped = false
  let initialFrames = 36, fallFrames = -1, fallStart = 0, landedFrames = 0
  let lastCubeScore = -9999
  const player: Player = { x: width / 2 - PLAYER_WIDTH / 2, y: height * .7 - PLAYER_HEIGHT, vx: 0, vy: 0, width: PLAYER_WIDTH, height: PLAYER_HEIGHT, squash: 1, direction: 'right' }
  const initialJump = -Math.sqrt(2 * GRAVITY * (height * .3 + PLAYER_HEIGHT + 60))
  const platforms: Platform[] = [{ x: width / 2 - 55, y: height * .7 - 30, width: 110, kind: 'normal', velocity: 0, crumbleFrames: null }]
  let cubes: SugarCube[] = []
  let top = platforms[0].y
  for (let index = 0; index < 9; index++) {
    const platformWidth = 70 + Math.random() * 45
    top -= 85 + Math.random() * 35
    platforms.push({ x: 10 + Math.random() * (width - platformWidth - 20), y: top, width: platformWidth, kind: Math.random() < .3 ? 'crumble' : 'normal', velocity: 0, crumbleFrames: null })
  }
  const input = attachInput(canvas)
  const render = createRenderer(context)
  const resize = () => {
    width = window.innerWidth; height = window.innerHeight
    // Logical pixels keep movement consistent on high-density phones.
    canvas.width = width; canvas.height = height
  }
  resize(); window.addEventListener('resize', resize)
  const visibility = () => { paused = document.hidden; previousTime = 0; accumulator = 0 }
  document.addEventListener('visibilitychange', visibility)

  const step = () => {
    if (initialFrames > 0) { initialFrames--; return }
    if (fallFrames >= 0) {
      if (landedFrames > 0) {
        player.squash = Math.min(1, player.squash + .04)
        if (++landedFrames > 45) { stopped = true; onEnd(score) }
        return
      }
      const fraction = Math.min(++fallFrames / 55, 1)
      const nextScroll = fallStart * (1 - fraction) ** 2
      const movement = nextScroll - scroll
      scroll = nextScroll; platforms.forEach(item => { item.y += movement }); cubes.forEach(item => { item.y += movement })
      player.vy += GRAVITY; player.y += player.vy
      if (fraction === 1) { player.y = height * .7 - player.height; player.vy = 0; player.squash = .65; landedFrames = 1 }
      return
    }
    if (player.vy === 0) player.vy = initialJump
    const steering = Math.abs(input.state.touch) > .05 ? input.state.touch * .55
      : input.state.left ? -.55 : input.state.right ? .55
      : Math.abs(input.state.tilt) > 5 ? input.state.tilt * .055 : 0
    player.vx = Math.max(-MAX_HORIZONTAL_SPEED, Math.min(MAX_HORIZONTAL_SPEED, player.vx + steering))
    if (!steering) player.vx *= .82
    player.x += player.vx; wrapsHorizontally(player, width)
    const previousBottom = player.y + player.height
    player.vy = rush > 0 ? BOUNCE_SPEED * 4 : player.vy + GRAVITY
    player.y += player.vy
    if (!rush) {
      const platform = landingPlatform(player, previousBottom, platforms)
      if (platform) { player.y = platform.y - player.height; player.vy = BOUNCE_SPEED; player.squash = .68; if (platform.kind === 'crumble') platform.crumbleFrames = 45 }
    }
    cubes = cubes.filter(cube => {
      const collected = player.x + player.width > cube.x && player.x < cube.x + 22 && player.y + player.height > cube.y && player.y < cube.y + 22
      if (collected) rush = SUGAR_RUSH_FRAMES
      return !collected
    })
    if (rush > 0) rush--
    player.squash = Math.min(1, player.squash + .065)
    if (player.y < height * CAMERA_LINE) {
      const movement = height * CAMERA_LINE - player.y
      player.y = height * CAMERA_LINE
      scroll += movement; platforms.forEach(item => { item.y += movement }); cubes.forEach(item => { item.y += movement })
      const nextScore = Math.floor(scroll / 7)
      if (nextScore !== score) { score = nextScore; onScore(score) }
    }
    for (let index = platforms.length - 1; index >= 0; index--) {
      const platform = platforms[index]
      if (platform.crumbleFrames !== null) platform.crumbleFrames--
      if (platform.y > height + 30 || (platform.crumbleFrames !== null && platform.crumbleFrames <= 0)) { platforms.splice(index, 1); continue }
      if (platform.kind === 'moving') {
        platform.x += platform.velocity
        if (platform.x <= 0) { platform.x = 0; platform.velocity = Math.abs(platform.velocity) }
        if (platform.x + platform.width >= width) { platform.x = width - platform.width; platform.velocity = -Math.abs(platform.velocity) }
      }
    }
    cubes = cubes.filter(cube => cube.y < height + 50)
    const highest = Math.min(height, ...platforms.map(platform => platform.y))
    if (highest > 60) {
      const platformWidth = 70 + Math.random() * 45
      const difficulty = Math.min(score / 5000, 1), chance = Math.random()
      const kind = chance < .03 + difficulty * .17 ? 'moving' : chance < .11 + difficulty * .37 ? 'crumble' : 'normal'
      platforms.push({ x: 10 + Math.random() * (width - platformWidth - 20), y: highest - (80 + Math.random() * 55), width: platformWidth, kind, velocity: kind === 'moving' ? (.7 + Math.random() * .7) * (Math.random() < .5 ? 1 : -1) : 0, crumbleFrames: null })
      if (!cubes.length && score - lastCubeScore > 500 && Math.random() < .07) { cubes.push({ x: 20 + Math.random() * (width - 62), y: highest - 130 - Math.random() * 60 }); lastCubeScore = score }
    }
    if (player.y > height + 80) { fallFrames = 0; fallStart = scroll; player.x = width / 2 - player.width / 2; player.y = height - player.height; player.vy = 8; player.vx = 0 }
    if (player.vx > .5) player.direction = 'right'
    else if (player.vx < -.5) player.direction = 'left'
  }

  // A fixed timestep preserves prototype speed at 60 Hz and avoids slower play
  // on low-refresh devices or accelerated physics on 120 Hz displays.
  let previousTime = 0, accumulator = 0
  const tick = (time: number) => {
    if (stopped) return
    const elapsed = previousTime ? Math.min(time - previousTime, 100) : 0
    previousTime = time
    if (!paused) {
      accumulator += elapsed
      while (accumulator >= 1000 / 60 && !stopped) { step(); accumulator -= 1000 / 60 }
      render({ width, height, scroll, player, platforms, cubes, rush, best, score })
    }
    if (!stopped) frame = requestAnimationFrame(tick)
  }
  frame = requestAnimationFrame(tick)
  return () => { stopped = true; cancelAnimationFrame(frame); input.cleanup(); window.removeEventListener('resize', resize); document.removeEventListener('visibilitychange', visibility) }
}
