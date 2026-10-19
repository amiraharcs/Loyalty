import { apiRequest, resetCsrf } from './client'

export interface CurrentUser {
  userId: string
  displayName: string | null
  roles: ('CUSTOMER' | 'STAFF' | 'ADMIN')[]
}
export const getCurrentUser = () => apiRequest<CurrentUser>('/api/v1/auth/me', { notifyAuthFailure: false })
export async function login(email: string, password: string) {
  const user = await apiRequest<CurrentUser>('/api/v1/auth/sessions', {
    method: 'POST', body: { email, password }, notifyAuthFailure: false,
  })
  resetCsrf() // Authentication rotates the token; the next unsafe request obtains a fresh one.
  return user
}
export async function logout() {
  await apiRequest<void>('/api/v1/auth/sessions/current', { method: 'DELETE' })
  resetCsrf()
}
