import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import * as api from '../../api/game'
import { startEngine } from '../../features/game/engine'
import GamePage from './GamePage'
vi.mock('../../api/game')
vi.mock('../../features/game/engine', () => ({ startEngine: vi.fn(() => vi.fn()) }))
const session: api.GameSession = {
  sessionId: 'server-id', status: 'ACTIVE', startedAt: '2026-10-18T10:00:00Z', expiresAt: '2026-10-18T10:15:00Z',
  engineVersion: 'v1', remainingLives: 2, nextRechargeAt: '2026-10-18T12:00:00Z', serverTime: '2026-10-18T10:00:00Z',
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(api.getGameLives).mockResolvedValue({ availableLives: 3, maxLives: 3, nextRechargeAt: null, serverTime: session.serverTime })
  vi.mocked(api.getGameSummary).mockResolvedValue({ personalBest: 10 })
  vi.mocked(api.startGameSession).mockResolvedValue(session)
})
it('keeps the engine stopped until 201, starts once, then shows saving and accepted state', async () => {
  let resolve!: (value: api.GameSession) => void
  vi.mocked(api.startGameSession).mockReturnValue(new Promise(done => { resolve = done }))
  let save!: (value: api.AcceptedScore) => void
  vi.mocked(api.submitGameScore).mockReturnValue(new Promise(done => { save = done }))
  render(<MemoryRouter><GamePage /></MemoryRouter>)
  expect(screen.getByRole('button', { name: 'Start Game' })).toBeDisabled()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Start Game' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Start Game' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Starting…' })).toBeDisabled())
  expect(startEngine).not.toHaveBeenCalled()
  await act(async () => resolve(session))
  await waitFor(() => expect(startEngine).toHaveBeenCalledTimes(1))
  const end = vi.mocked(startEngine).mock.calls[0][3]
  await act(async () => { end(50); end(100) })
  expect(screen.getByText('Saving score…')).toBeInTheDocument()
  expect(screen.getByText('50')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Play Again' })).toBeDisabled()
  expect(api.submitGameScore).toHaveBeenCalledTimes(1)
  vi.mocked(api.getGameSummary).mockResolvedValue({ personalBest: 50 })
  await act(async () => save({ scoreId: 'score-id', sessionId: session.sessionId, score: 50, acceptedAt: session.serverTime, sessionStatus: 'COMPLETED' }))
  expect(screen.getByText('Score saved')).toBeInTheDocument()
  expect(startEngine).toHaveBeenCalledTimes(1)
})
it('summary resolving during play cannot create a second engine loop', async () => {
  let summary!: (value: api.GameSummary) => void
  vi.mocked(api.getGameSummary).mockReturnValue(new Promise(done => { summary = done }))
  const { unmount } = render(<MemoryRouter><GamePage /></MemoryRouter>)
  await waitFor(() => expect(screen.getByRole('button', { name: 'Start Game' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Start Game' }))
  await waitFor(() => expect(startEngine).toHaveBeenCalledTimes(1))
  await act(async () => summary({ personalBest: 100 }))
  expect(startEngine).toHaveBeenCalledTimes(1)
  const cleanup = vi.mocked(startEngine).mock.results[0].value
  unmount(); expect(cleanup).toHaveBeenCalledOnce()
})
