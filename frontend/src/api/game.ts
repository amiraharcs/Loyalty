import { apiRequest } from './client'

export interface GameLives {
  availableLives: number
  maxLives: number
  nextRechargeAt: string | null
  serverTime: string
}
export interface GameSession {
  sessionId: string
  status: 'ACTIVE' | 'COMPLETED' | 'EXPIRED'
  startedAt: string
  expiresAt: string
  engineVersion: string
  remainingLives: number
  nextRechargeAt: string | null
  serverTime: string
}
export interface AcceptedScore {
  scoreId: string
  sessionId: string
  score: number
  acceptedAt: string
  sessionStatus: 'COMPLETED'
}
export interface GameSummary { personalBest: number | null }
const base = '/api/v1/customers/me'
export const getGameLives = () => apiRequest<GameLives>(`${base}/game-lives`)
export const getGameSummary = () => apiRequest<GameSummary>(`${base}/game-summary`)
export const startGameSession = (key: string) => apiRequest<GameSession>(`${base}/game-sessions`, { method: 'POST', key })
export const getGameSession = (id: string) => apiRequest<GameSession>(`${base}/game-sessions/${encodeURIComponent(id)}`)
export const submitGameScore = (id: string, score: number, key: string) => apiRequest<AcceptedScore>(
  `${base}/game-sessions/${encodeURIComponent(id)}/scores`, { method: 'POST', key, body: { score } },
)
export const getAcceptedScore = (id: string) => apiRequest<AcceptedScore>(`${base}/game-sessions/${encodeURIComponent(id)}/scores`)
