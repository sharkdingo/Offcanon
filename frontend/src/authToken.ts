let token: string | null = null

export function getAuthToken() {
  return token
}

export function setAuthToken(value: string | null) {
  token = value
}
