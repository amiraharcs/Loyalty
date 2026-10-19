import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e', timeout: 180_000, workers: 1,
  use: { baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:5173', viewport: { width: 390, height: 844 }, trace: 'retain-on-failure' },
  reporter: [['list']],
})
