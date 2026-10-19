import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import BrandLogo from '../../components/layout/BrandLogo'
import Button from '../../components/ui/Button'
import { ApiError } from '../../api/client'
import { useSession } from '../../features/session/SessionContext'

export default function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const { signIn } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const inputClass = 'mt-1.5 w-full rounded-2xl border border-transparent bg-white px-4 py-4 text-[15px] outline-none transition-colors focus:border-[#1A1A1A]'
  return (
    <main className="min-h-screen px-6 pt-20 pb-10">
      <div className="mb-14"><BrandLogo /></div>
      <h1 className="mb-2 text-[34px] leading-tight font-bold">Welcome back</h1>
      <p className="mb-10 text-[15px] text-muted">Sign in to your loyalty account</p>
      <form className="space-y-4" onSubmit={async event => {
        event.preventDefault()
        if (!email.trim() || !password) { setError('Please fill in all fields'); return }
        if (pending) return
        setPending(true); setError('')
        try {
          await signIn(email.trim(), password)
          const from: unknown = location.state?.from
          navigate(typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') ? from : '/home', { replace: true })
        } catch (cause) { setError(cause instanceof ApiError && cause.status === 401 ? 'Email or password is incorrect.' : 'Could not sign in. Please retry.') }
        finally { setPending(false) }
      }}>
        <label className="block"><span className="text-[12px] font-medium tracking-widest uppercase text-muted">Email</span><input required type="email" autoComplete="email" placeholder="you@example.com" value={email} onChange={event => setEmail(event.target.value)} className={inputClass} /></label>
        <label className="block"><span className="text-[12px] font-medium tracking-widest uppercase text-muted">Password</span><input required type="password" autoComplete="current-password" placeholder="••••••••" value={password} onChange={event => setPassword(event.target.value)} className={inputClass} /></label>
        {error && <p role="alert" className="text-[13px] text-red-500">{error}</p>}
        <Button type="submit" disabled={pending}>{pending ? 'Signing in…' : 'Sign in'}</Button>
      </form>
      <p className="mt-10 text-center text-[12px] text-muted">Use your registered customer account</p>
    </main>
  )
}
