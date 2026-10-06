import { Navigate, Route, Routes } from 'react-router-dom'
import Home from '../pages/Home/Home'
import CardPage from '../pages/Card/CardPage'
import GamePage from '../pages/Game/GamePage'
import LeaderboardPage from '../pages/Leaderboard/LeaderboardPage'
import LoginPage from '../pages/Login/LoginPage'
import AppShell from '../components/layout/AppShell'
import RequireSession from './RequireSession'

export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/home" replace />} />
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireSession />}>
        <Route element={<AppShell />}>
          <Route path="/home" element={<Home />} />
          <Route path="/card" element={<CardPage />} />
          <Route path="/game" element={<GamePage />} />
          <Route path="/leaderboard" element={<LeaderboardPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/home" replace />} />
    </Routes>
  )
}
