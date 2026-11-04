import type { Beer, CreateBeerRequest, PatchBeerRequest } from './types'

// Single place every beer API call goes through. Later this is where the
// capture session header and auth token get attached.
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!response.ok) {
    throw new ApiError(response.status, await describeError(response))
  }
  return (await response.json()) as T
}

async function describeError(response: Response): Promise<string> {
  switch (response.status) {
    case 400:
      return 'The beer service rejected that input. Check every field is filled in and ABV is 0 or more.'
    case 404:
      return 'That beer no longer exists.'
    case 429:
      return 'The beer service is rate limited. Wait a moment and try again.'
    case 502:
      return 'The capture proxy could not reach the beer service.'
    case 504:
      return 'The beer service took too long to respond.'
    default: {
      const text = await response.text().catch(() => '')
      return `Request failed (${response.status})${text ? `: ${text}` : ''}`
    }
  }
}

export const beerApi = {
  list: () => request<Beer[]>('/api/beers'),
  get: (id: string) => request<Beer>(`/api/beers/${encodeURIComponent(id)}`),
  create: (body: CreateBeerRequest) =>
    request<Beer>('/api/beers', { method: 'POST', body: JSON.stringify(body) }),
  patch: (id: string, body: PatchBeerRequest) =>
    request<Beer>(`/api/beers/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    }),
}
