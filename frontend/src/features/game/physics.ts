import { PLATFORM_HEIGHT } from './constants'
import type { Platform, Player } from './types'

export function landingPlatform(player: Player, previousBottom: number, platforms: Platform[]) {
  if (player.vy <= 0) return undefined
  const bottom = player.y + player.height
  return platforms.find(platform => platform.crumbleFrames === null
    && previousBottom <= platform.y + PLATFORM_HEIGHT
    && bottom >= platform.y
    && bottom <= platform.y + PLATFORM_HEIGHT + Math.max(player.vy, 2) + 2
    && player.x + player.width > platform.x + 6
    && player.x < platform.x + platform.width - 6)
}

export function wrapsHorizontally(player: Player, width: number) {
  if (player.x + player.width < 0) player.x = width
  else if (player.x > width) player.x = -player.width
}
