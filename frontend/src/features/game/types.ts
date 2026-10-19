export type PlatformKind = 'normal' | 'crumble' | 'moving'
export interface Platform {
  x: number; y: number; width: number; kind: PlatformKind
  velocity: number; crumbleFrames: number | null
}
export interface Player {
  x: number; y: number; vx: number; vy: number
  width: number; height: number; squash: number; direction: 'left' | 'right'
}
export interface SugarCube { x: number; y: number }
export type GamePhase = 'idle' | 'playing' | 'gameover'
export interface GameResult { score: number; best: number | null; newBest: boolean }
