import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'

const sessions = '/api/v1/customers/me/game-sessions'
const lives = '/api/v1/customers/me/game-lives'
const summary = '/api/v1/customers/me/game-summary'
async function customer(page: Page) {
  const email = `game-browser-${randomUUID()}@example.org`
  const password = `private browser test phrase ${randomUUID()}`
  const csrfResponse = await page.request.get('/api/v1/auth/csrf')
  const csrf: { token: string } = await csrfResponse.json()
  const registration = await page.request.post('/api/v1/auth/registrations', {
    headers: { 'X-CSRF-TOKEN': csrf.token }, data: { email, password, displayName: 'Haroon Browser' },
  })
  expect(registration.status()).toBe(201)
  await page.goto('/game')
  await expect(page).toHaveURL(/\/login$/)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(/\/game$/)
  await expect(page.getByRole('button', { name: 'Start Game', exact: true })).toBeEnabled()
}
async function runToEnd(page: Page) {
  const start = page.waitForResponse(response => response.url().endsWith(sessions) && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Start Game', exact: true }).click()
  expect((await start).status()).toBe(201)
  await expect(page.locator('canvas.game-canvas')).toBeVisible()
  await page.keyboard.down('ArrowRight')
  await expect(page.getByRole('heading', { name: 'Game Over', exact: true })).toBeVisible({ timeout: 90_000 })
  await page.keyboard.up('ArrowRight')
}

test('real cookie login, three engine runs, immutable scores, persisted best/lives and fourth-start prevention', async ({ page }) => {
  await customer(page)
  await expect(page.getByLabel('3 of 3 lives remaining')).toBeVisible()
  const requests: { key: string | undefined; body: unknown }[] = []
  page.on('request', request => {
    if (request.url().endsWith('/scores') && request.method() === 'POST') requests.push({ key: request.headers()['idempotency-key'], body: request.postDataJSON() })
  })
  for (let index = 0; index < 3; index++) {
    await runToEnd(page)
    await expect(page.getByText('Score saved', { exact: true })).toBeVisible()
    await expect(page.getByLabel(`${2 - index} of 3 lives remaining`)).toBeVisible()
    if (index < 2) await page.getByRole('button', { name: 'Play Again', exact: true }).click()
  }
  expect(requests).toHaveLength(3)
  for (const request of requests) {
    expect(request.key).toMatch(/^[0-9a-f-]{36}$/)
    expect(Object.keys(request.body as object)).toEqual(['score'])
  }
  await expect(page.getByRole('button', { name: 'No lives left', exact: true })).toBeDisabled()
  const serverLives = await (await page.request.get(lives)).json()
  const serverSummary = await (await page.request.get(summary)).json()
  expect(serverLives.availableLives).toBe(0)
  expect(serverSummary.personalBest).toBeGreaterThanOrEqual(0)
  await page.reload()
  await expect(page.getByRole('button', { name: 'No lives left', exact: true })).toBeDisabled()
  await expect(page.getByText('Personal Best:', { exact: false })).toContainText(Number(serverSummary.personalBest).toLocaleString())
  await expect(page.getByText('Next life in:', { exact: false })).toBeVisible()
  await page.screenshot({ path: '/tmp/loyalty-issue31-game-zero-lives.png', fullPage: true })
})

test('refresh abandons the run without a refund and reports the undiscoverable active session safely', async ({ page }) => {
  await customer(page)
  await page.getByRole('button', { name: 'Start Game', exact: true }).click()
  await expect(page.locator('canvas.game-canvas')).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('2 of 3 lives remaining')).toBeVisible()
  await page.getByRole('button', { name: 'Start Game', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('A game is already in progress')
  await expect(page.getByRole('button', { name: 'Start Game', exact: true })).toBeDisabled()
  expect((await (await page.request.get(lives)).json()).availableLives).toBe(2)
})

test('lost start response retries the same key, validates owned recovery and starts only once', async ({ page }) => {
  await customer(page)
  const keys: string[] = []
  page.on('request', request => { if (request.url().endsWith(sessions) && request.method() === 'POST') keys.push(request.headers()['idempotency-key']) })
  await page.route(`**${sessions}`, async route => {
    if (route.request().method() !== 'POST') return route.continue()
    const response = await route.fetch()
    expect(response.status()).toBe(201)
    await route.abort('failed')
  })
  await page.getByRole('button', { name: 'Start Game', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Retry start', exact: true })).toBeEnabled()
  await expect(page.locator('canvas.game-canvas')).toHaveCount(0)
  await page.unroute(`**${sessions}`)
  await page.getByRole('button', { name: 'Retry start', exact: true }).click()
  await expect(page.locator('canvas.game-canvas')).toBeVisible()
  expect(keys).toHaveLength(2); expect(keys[0]).toBe(keys[1])
  expect((await (await page.request.get(lives)).json()).availableLives).toBe(2)
})

test('lost accepted-score response recovers via the identical frozen payload/key', async ({ page }) => {
  await customer(page)
  const submissions: { key: string; body: unknown }[] = []
  page.on('request', request => {
    if (request.url().endsWith('/scores') && request.method() === 'POST') submissions.push({ key: request.headers()['idempotency-key'], body: request.postDataJSON() })
  })
  await page.route('**/scores', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    expect((await route.fetch()).status()).toBe(201)
    await route.abort('failed')
  })
  await runToEnd(page)
  await expect(page.getByRole('button', { name: 'Retry saving score', exact: true })).toBeEnabled()
  await page.unroute('**/scores')
  await page.getByRole('button', { name: 'Retry saving score', exact: true }).click()
  await expect(page.getByText('Score saved', { exact: true })).toBeVisible()
  expect(submissions).toHaveLength(2); expect(submissions[0]).toEqual(submissions[1])
  expect((await (await page.request.get(lives)).json()).availableLives).toBe(2)
})

test('actual server plausibility rejection shows safe copy and does not refund the consumed life', async ({ page }) => {
  await customer(page)
  await page.route('**/scores', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    const response = await route.fetch({ postData: JSON.stringify({ score: 9007199254740991 }) })
    expect(response.status()).toBe(409)
    await route.fulfill({ response })
  })
  await runToEnd(page)
  await expect(page.getByRole('alert')).toContainText("We couldn't verify this run")
  await expect(page.getByText('Score saved', { exact: true })).toHaveCount(0)
  expect((await (await page.request.get(lives)).json()).availableLives).toBe(2)
  expect((await (await page.request.get(summary)).json()).personalBest).toBeNull()
})
