import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import * as auth from '../../api/auth'
import { ApiError } from '../../api/client'
import { useSession } from './SessionContext'
import SessionProvider from './SessionProvider'
vi.mock('../../api/auth')
function Consumer() {
  const session = useSession()
  return <><span>{session.status}</span><span>{session.member?.name}</span>
    <button onClick={() => void session.signIn('customer@example.org', 'private password')}>Sign in</button>
    <button onClick={() => void session.reload()}>Retry</button></>
}
beforeEach(() => vi.resetAllMocks())
it('bootstraps from the cookie session and ignores a forged localStorage member', async () => {
  localStorage.setItem('loyalty.member.v1', JSON.stringify({ name: 'forged', points: 99999 }))
  vi.mocked(auth.getCurrentUser).mockResolvedValue({ userId: 'id', displayName: 'Server Haroon', roles: ['CUSTOMER'] })
  render(<SessionProvider><Consumer /></SessionProvider>)
  await screen.findByText('authenticated')
  expect(screen.getByText('Server Haroon')).toBeInTheDocument()
  expect(screen.queryByText('forged')).not.toBeInTheDocument()
  localStorage.clear()
})
it('a game API 401 enters the shared logged-out state', async () => {
  vi.mocked(auth.getCurrentUser).mockResolvedValue({ userId: 'id', displayName: 'Haroon', roles: ['CUSTOMER'] })
  render(<SessionProvider><Consumer /></SessionProvider>)
  await screen.findByText('authenticated')
  act(() => window.dispatchEvent(new Event('loyalty:unauthenticated')))
  expect(screen.getByText('anonymous')).toBeInTheDocument()
})
it('network failure remains an explicit bootstrap error rather than a fake session', async () => {
  vi.mocked(auth.getCurrentUser).mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ userId: 'id', displayName: 'Haroon', roles: ['CUSTOMER'] })
  render(<SessionProvider><Consumer /></SessionProvider>)
  await screen.findByText('error')
  fireEvent.click(screen.getByText('Retry'))
  await screen.findByText('authenticated')
})
it('anonymous bootstrap followed by real login uses the server identity', async () => {
  vi.mocked(auth.getCurrentUser).mockRejectedValue(new ApiError(401))
  vi.mocked(auth.login).mockResolvedValue({ userId: 'id', displayName: 'Haroon', roles: ['CUSTOMER'] })
  render(<SessionProvider><Consumer /></SessionProvider>)
  await screen.findByText('anonymous')
  fireEvent.click(screen.getByText('Sign in'))
  await waitFor(() => expect(auth.login).toHaveBeenCalledWith('customer@example.org', 'private password'))
  await screen.findByText('authenticated')
})
