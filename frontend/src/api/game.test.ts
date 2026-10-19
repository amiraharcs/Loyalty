import { beforeEach, expect, it, vi } from 'vitest'
import { apiRequest, ApiError, resetCsrf } from './client'
import { login } from './auth'
import { getGameLives, getGameSession, getGameSummary, startGameSession, submitGameScore } from './game'
const fetcher = vi.fn<typeof fetch>()
const response = (body: unknown, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers })
beforeEach(() => { resetCsrf(); fetcher.mockReset(); vi.stubGlobal('fetch', fetcher) })
it('uses same-origin cookies for typed reads without any bearer token', async () => {
  fetcher.mockImplementation(async () => response({}))
  await getGameLives(); await getGameSession('owned-id'); await getGameSummary()
  expect(fetcher.mock.calls.map(call => call[0])).toEqual(['/api/v1/customers/me/game-lives', '/api/v1/customers/me/game-sessions/owned-id', '/api/v1/customers/me/game-summary'])
  for (const [, options] of fetcher.mock.calls) {
    expect(options?.credentials).toBe('include'); expect(options?.headers).not.toHaveProperty('Authorization')
  }
})
it('gets CSRF before start and sends only score with its stable idempotency key', async () => {
  fetcher.mockResolvedValueOnce(response({ token: 'fresh' })).mockImplementation(async () => response({}))
  await startGameSession('start-key'); await submitGameScore('owned-id', 50, 'score-key')
  expect(fetcher.mock.calls[0][0]).toBe('/api/v1/auth/csrf')
  expect(fetcher.mock.calls[1][1]).toMatchObject({ method: 'POST', headers: { 'X-CSRF-TOKEN': 'fresh', 'Idempotency-Key': 'start-key' } })
  expect(fetcher.mock.calls[1][1]?.body).toBeUndefined()
  expect(JSON.parse(String(fetcher.mock.calls[2][1]?.body))).toEqual({ score: 50 })
  expect(fetcher.mock.calls[2][1]?.headers).toMatchObject({ 'Idempotency-Key': 'score-key', 'X-CSRF-TOKEN': 'fresh' })
})
it('refreshes CSRF after login rotates the browser session', async () => {
  fetcher.mockResolvedValueOnce(response({ token: 'visitor' })).mockResolvedValueOnce(response({ userId: 'id', displayName: 'Haroon', roles: ['CUSTOMER'] }, 201))
    .mockResolvedValueOnce(response({ token: 'authenticated' })).mockResolvedValueOnce(response({}))
  await login('customer@example.org', 'private password')
  await startGameSession('key')
  expect(fetcher.mock.calls[3][1]?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'authenticated' })
})
it('parses Problem Details and Retry-After, retaining request correlation', async () => {
  fetcher.mockResolvedValue(response({ status: 429, code: 'RATE_LIMITED', detail: 'safe detail', requestId: 'request' }, 429, { 'Retry-After': '7' }))
  await expect(apiRequest('/api/test')).rejects.toMatchObject({ status: 429, code: 'RATE_LIMITED', requestId: 'request', retryAfter: 7 })
})
it('401 signals the shared logged-out UX and clears cached CSRF', async () => {
  const expired = vi.fn(); window.addEventListener('loyalty:unauthenticated', expired)
  fetcher.mockResolvedValue(response({ code: 'UNAUTHENTICATED' }, 401))
  await expect(getGameLives()).rejects.toBeInstanceOf(ApiError)
  expect(expired).toHaveBeenCalledOnce(); window.removeEventListener('loyalty:unauthenticated', expired)
})
it('never automatically retries ambiguous unsafe requests', async () => {
  fetcher.mockResolvedValueOnce(response({ token: 'fresh' })).mockRejectedValueOnce(new TypeError('offline'))
  await expect(submitGameScore('id', 10, 'same-key')).rejects.toThrow('offline')
  expect(fetcher).toHaveBeenCalledTimes(2)
})
