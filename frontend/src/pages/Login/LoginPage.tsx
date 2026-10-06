import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BrandLogo from '../../components/layout/BrandLogo'
import Button from '../../components/ui/Button'
import { useSession } from '../../features/session/SessionContext'

export default function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const { signIn } = useSession()
  const navigate = useNavigate()
  const inputClass = 'mt-1.5 w-full rounded-2xl border border-transparent bg-white px-4 py-4 text-[15px] outline-none transition-colors focus:border-[#1A1A1A]'
  return (
    <main className="min-h-screen px-6 pt-20 pb-10">
      <div className="mb-14"><BrandLogo /></div>
      <h1 className="mb-2 text-[34px] leading-tight font-bold">Welcome back</h1>
      <p className="mb-10 text-[15px] text-muted">Sign in to your loyalty account</p>
      <form className="space-y-4" onSubmit={event => {
        event.preventDefault()
        if (!email.trim() || !password) { setError('Please fill in all fields'); return }
        signIn(email.trim()); navigate('/home')
      }}>
        <label className="block"><span className="text-[12px] font-medium tracking-widest uppercase text-muted">Email</span><input required type="email" autoComplete="email" placeholder="you@example.com" value={email} onChange={event => setEmail(event.target.value)} className={inputClass} /></label>
        <label className="block"><span className="text-[12px] font-medium tracking-widest uppercase text-muted">Password</span><input required type="password" autoComplete="current-password" placeholder="••••••••" value={password} onChange={event => setPassword(event.target.value)} className={inputClass} /></label>
        {error && <p role="alert" className="text-[13px] text-red-500">{error}</p>}
        <Button type="submit">Sign in</Button>
      </form>
      <p className="mt-10 text-center text-[12px] text-muted">Demo mode — any email and password will work</p>
    </main>
  )
}
