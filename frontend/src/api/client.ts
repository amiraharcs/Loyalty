export interface ProblemDetails {
  status?: number
  code?: string
  detail?: string
  requestId?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId?: string
  readonly retryAfter: number
  constructor(status: number, problem: ProblemDetails = {}, retryAfter = 0) {
    super(problem.detail || 'The request could not be completed.')
    this.name = 'ApiError'
    this.status = status
    this.code = problem.code || 'REQUEST_FAILED'
    this.requestId = problem.requestId
    this.retryAfter = retryAfter
  }
}

let csrf: Promise<string> | undefined
export function resetCsrf() { csrf = undefined }

function retrySeconds(value: string | null): number {
  if (!value) return 0
  const seconds = Number(value)
  return Math.max(0, Number.isFinite(seconds) ? seconds : Math.ceil((Date.parse(value) - Date.now()) / 1000) || 0)
}

interface Options {
  method?: 'GET' | 'POST' | 'DELETE'
  body?: unknown
  key?: string
  notifyAuthFailure?: boolean
}

/** Same-origin cookies only. Unsafe timeouts are ambiguous: callers must retain their key. */
export async function apiRequest<T>(path: string, options: Options = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (method !== 'GET') {
    csrf ??= apiRequest<{ token: string }>('/api/v1/auth/csrf', { notifyAuthFailure: false })
      .then(result => result.token).catch(error => { resetCsrf(); throw error })
    headers['X-CSRF-TOKEN'] = await csrf
  }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.key) headers['Idempotency-Key'] = options.key
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 15_000)
  try {
    const response = await fetch(path, {
      method, headers, credentials: 'include', cache: 'no-store', signal: controller.signal,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
    if (!response.ok) {
      const raw: unknown = await response.json().catch(() => null)
      const problem: ProblemDetails = raw && typeof raw === 'object' ? raw : {}
      if (response.status === 401 || response.status === 403) resetCsrf()
      if (response.status === 401 && options.notifyAuthFailure !== false) {
        window.dispatchEvent(new Event('loyalty:unauthenticated'))
      }
      throw new ApiError(response.status, problem, retrySeconds(response.headers.get('Retry-After')))
    }
    return response.status === 204 ? undefined as T : await response.json() as T
  } finally { clearTimeout(timeout) }
}
