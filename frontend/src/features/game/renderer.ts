import shopUrl from '../../assets/game/shop.png'
import rightIdleUrl from '../../assets/game/bean-right-idle.png'
import rightJumpUrl from '../../assets/game/bean-right-jump.png'
import leftIdleUrl from '../../assets/game/bean-left-idle.png'
import leftJumpUrl from '../../assets/game/bean-left-jump.png'
import type { Platform, Player, SugarCube } from './types'

function loadImage(url: string) { const image = new Image(); image.src = url; return image }
export function createRenderer(context: CanvasRenderingContext2D) {
  const shop = loadImage(shopUrl)
  const sprites = { right: { idle: loadImage(rightIdleUrl), jump: loadImage(rightJumpUrl) }, left: { idle: loadImage(leftIdleUrl), jump: loadImage(leftJumpUrl) } }
  const tile = document.createElement('canvas'); tile.width = tile.height = 110
  const pen = tile.getContext('2d')!
  pen.strokeStyle = 'rgba(0,0,0,.07)'; pen.lineWidth = 1.5
  for (const [x, y, angle, rx, ry] of [[28, 32, -.4, 13, 8.5], [82, 80, .9, 10, 6.5]]) {
    pen.save(); pen.translate(x, y); pen.rotate(angle); pen.beginPath(); pen.ellipse(0, 0, rx, ry, 0, 0, Math.PI * 2); pen.stroke()
    pen.beginPath(); pen.moveTo(0, -ry); pen.bezierCurveTo(5, -3, 5, 3, 0, ry); pen.stroke(); pen.restore()
  }
  for (const [x, y] of [[75, 28], [18, 82]]) { pen.beginPath(); pen.moveTo(x, y + 14); pen.bezierCurveTo(x - 7, y + 7, x + 7, y + 2, x, y - 6); pen.stroke() }
  for (const [x, y] of [[55, 18], [90, 50], [40, 90], [10, 55]]) { pen.beginPath(); pen.arc(x, y, 2.2, 0, Math.PI * 2); pen.fillStyle = 'rgba(0,0,0,.07)'; pen.fill() }
  const pattern = context.createPattern(tile, 'repeat')

  const platform = (item: Platform) => {
    context.save()
    const crumble = item.crumbleFrames === null ? 0 : 1 - item.crumbleFrames / 45
    context.globalAlpha = 1 - crumble
    const fill = item.kind === 'normal' ? '#FFFFFF' : item.kind === 'moving' ? '#2ECC71' : '#F4A261'
    const stroke = item.kind === 'moving' ? '#1A4A3A' : '#1A1A1A'
    const pieces = crumble ? 3 : 1
    for (let index = 0; index < pieces; index++) {
      const width = item.width / pieces
      const x = item.x + index * width, y = item.y + crumble * 80 * (1 + index * .25)
      context.beginPath(); context.roundRect(x + 2, y + 5, width, 13, 6.5); context.fillStyle = stroke; context.fill()
      context.beginPath(); context.roundRect(x, y, width, 13, 6.5); context.fillStyle = fill; context.fill(); context.strokeStyle = stroke; context.lineWidth = 2.5; context.stroke()
    }
    context.restore()
  }

  return (scene: { width: number; height: number; scroll: number; player: Player; platforms: Platform[]; cubes: SugarCube[]; rush: number; best: number; score: number }) => {
    const { width, height, scroll, player, platforms, cubes, rush, best, score } = scene
    if (rush) {
      const hue = Date.now() / 15 % 360
      const gradient = context.createLinearGradient(0, 0, width, height)
      gradient.addColorStop(0, `hsl(${hue},80%,88%)`); gradient.addColorStop(.5, `hsl(${(hue + 120) % 360},80%,88%)`); gradient.addColorStop(1, `hsl(${(hue + 240) % 360},80%,88%)`)
      context.fillStyle = gradient
    } else context.fillStyle = '#FAF0E4'
    context.fillRect(0, 0, width, height)
    if (pattern && !rush) { context.save(); context.translate(0, scroll % 110); context.fillStyle = pattern; context.fillRect(0, -110, width, height + 220); context.restore() }
    platforms.forEach(platform)
    for (const cube of cubes) {
      context.fillStyle = 'rgba(0,0,0,.22)'; context.fillRect(cube.x + 3, cube.y + 4, 22, 22)
      context.beginPath(); context.roundRect(cube.x, cube.y, 22, 22, 4); context.fillStyle = 'white'; context.fill(); context.strokeStyle = '#1A1A1A'; context.lineWidth = 2; context.stroke()
      context.strokeStyle = 'rgba(0,0,0,.12)'; context.lineWidth = 1; context.beginPath(); context.moveTo(cube.x + 11, cube.y + 2); context.lineTo(cube.x + 11, cube.y + 20); context.moveTo(cube.x + 2, cube.y + 11); context.lineTo(cube.x + 20, cube.y + 11); context.stroke()
    }
    if (best > 0) {
      const lineY = height * .38 + scroll - best * 7
      if (lineY >= 0 && lineY <= height) { context.save(); context.setLineDash([10, 6]); context.strokeStyle = 'rgba(26,26,26,.22)'; context.beginPath(); context.moveTo(0, lineY); context.lineTo(width, lineY); context.stroke(); context.setLineDash([]); context.textAlign = 'right'; context.fillStyle = 'rgba(26,26,26,.35)'; context.font = 'bold 10px Geist, sans-serif'; context.fillText(score > best ? 'NEW PERSONAL BEST' : 'BEAT THIS', width - 8, lineY - 5); context.restore() }
    }
    if (shop.complete && shop.naturalWidth && scroll < height + 100) {
      const ratio = shop.naturalWidth / shop.naturalHeight
      const drawHeight = Math.min(width / ratio, height), drawWidth = drawHeight * ratio
      context.drawImage(shop, (width - drawWidth) / 2, height + scroll - drawHeight, drawWidth, drawHeight)
    }
    const sprite = sprites[player.direction][player.vy < -2 ? 'jump' : 'idle']
    if (sprite.complete && sprite.naturalWidth) {
      const drawWidth = player.width * (1 + (1 - player.squash) * .3), drawHeight = player.height * player.squash
      context.drawImage(sprite, player.x + (player.width - drawWidth) / 2, player.y + player.height - drawHeight, drawWidth, drawHeight)
    }
    if (rush && Math.floor(Date.now() / 180) % 2 === 0) {
      context.save(); context.textAlign = 'center'; context.font = `900 ${Math.round(width * .1)}px Geist, sans-serif`; context.lineWidth = 6; context.strokeStyle = '#1A1A1A'; context.strokeText('SUGAR RUSH', width / 2, height * .18); context.fillStyle = `hsl(${Date.now() / 10 % 360},90%,55%)`; context.fillText('SUGAR RUSH', width / 2, height * .18); context.restore()
    }
  }
}
