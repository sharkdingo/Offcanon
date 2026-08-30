/** Emitted when an authenticated API request is rejected by the server. */
export const AUTH_UNAUTHORIZED_EVENT = 'offcanon:unauthorized'

export function notifyUnauthorized() {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new Event(AUTH_UNAUTHORIZED_EVENT))
}
