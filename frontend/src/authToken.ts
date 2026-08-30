let token: string | null = null

/** Emitted when an authenticated API request is rejected by the server. */
export const AUTH_UNAUTHORIZED_EVENT = 'offcanon:unauthorized'

export function getAuthToken() {
  return token
}

export function setAuthToken(value: string | null) {
  token = value
}

export function notifyUnauthorized(failedToken: string | null) {
  if (typeof window === 'undefined' || !failedToken) return
  window.dispatchEvent(new CustomEvent(AUTH_UNAUTHORIZED_EVENT, { detail: failedToken }))
}
