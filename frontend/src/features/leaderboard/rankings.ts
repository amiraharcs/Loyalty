import { readStored, writeStored } from '../../utils/storage'

export interface Ranking { id: string; name: string; score: number; friend: boolean; rank: number; current: boolean }
const opponents = [
  { id: 'p1', name: 'Jordan', score: 9200, friend: true },
  { id: 'p2', name: 'Taylor', score: 7650, friend: true },
  { id: 'p3', name: 'Sam', score: 6400, friend: false },
  { id: 'p4', name: 'Casey', score: 5100, friend: false },
  { id: 'p5', name: 'Megan', score: 4300, friend: true },
  { id: 'p6', name: 'Riley', score: 3200, friend: false },
  { id: 'p7', name: 'Morgan', score: 2600, friend: false },
  { id: 'p8', name: 'Jamie', score: 1800, friend: false },
]
export const prizes: Record<number, string> = { 1: '☕ Free Coffee', 2: '50% off', 3: '25% off', 4: '10% off' }
export const competitors = opponents.map((entry, index) => ({ ...entry, rank: index + 1 }))
export function personalBest() { return readStored<number>('loyalty.game.best', 0) }
export function saveScore(email: string, score: number) {
  writeStored('loyalty.game.best', Math.max(personalBest(), score))
  const key = `loyalty.weekly.${email}`
  writeStored(key, Math.max(readStored<number>(key, 0), score))
}
export function rankings(email: string, name: string, friends = false): Ranking[] {
  const entries = [...opponents, { id: email, name, score: readStored<number>(`loyalty.weekly.${email}`, 0), friend: false }]
    .sort((a, b) => b.score - a.score)
    .filter(entry => !friends || entry.friend || entry.id === email)
  return entries.map((entry, index) => ({ ...entry, rank: index + 1, current: entry.id === email }))
}
