import { ApiError } from '../../api/client'

export function gameErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Could not reach the server. Please retry.'
  if (error.status === 401) return 'Please sign in again to play.'
  if (error.status === 403) return 'This account cannot access the game. Please sign in with a customer account.'
  if (error.status === 429) return 'Too many requests. Please wait before retrying.'
  switch (error.code) {
    case 'NO_GAME_LIVES': return 'No lives left. Your next life is recharging.'
    case 'GAME_SESSION_ACTIVE': return 'A game is already in progress. Reload after it expires (up to 15 minutes).'
    case 'SCORE_IMPLAUSIBLE': return "We couldn't verify this run. Please start a new game."
    case 'SESSION_EXPIRED': return 'This run expired. Please start a new game.'
    case 'SCORE_ALREADY_SUBMITTED': return 'A different score was already saved for this run.'
    case 'IDEMPOTENCY_KEY_REUSED': return 'This request could not be recovered. Please reload the game.'
    default: return 'The request could not be completed. Please retry.'
  }
}
