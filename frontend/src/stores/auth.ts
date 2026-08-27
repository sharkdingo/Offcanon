import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, type AuthUser, type UserSettings } from '../api'
import { setAuthToken } from '../authToken'

export type AuthMode = 'api'
export type ThemeMode = 'dark' | 'light' | 'system'
export type Locale = 'zh-CN' | 'en-US'

export type AuthSession = {
  subject: string
  displayName: string
  mode: AuthMode
  signedInAt: string
  token: string
  expiresAt: string
  user: AuthUser
}

const AUTH_STORAGE_KEY = 'offcanon.auth.v2'
const ONBOARDING_STORAGE_PREFIX = 'offcanon.onboarding.v2.'
const THEME_STORAGE_KEY = 'offcanon.theme.v2'
const LOCALE_STORAGE_KEY = 'offcanon.locale.v1'

function readJson<T>(key: string): T | null {
  try {
    const value = window.localStorage.getItem(key)
    return value ? JSON.parse(value) as T : null
  } catch {
    return null
  }
}

function savedValue(key: string) {
  try { return window.localStorage.getItem(key) } catch { return null }
}

function saveValue(key: string, value: string) {
  try { window.localStorage.setItem(key, value) } catch { /* memory state remains authoritative */ }
}

function removeValue(key: string) {
  try { window.localStorage.removeItem(key) } catch { /* memory state remains authoritative */ }
}

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(null)
  const onboardingComplete = ref(false)
  const theme = ref<ThemeMode>('system')
  const locale = ref<Locale>('zh-CN')
  const ready = ref(false)
  const isAuthenticated = computed(() => session.value !== null)
  const needsOnboarding = computed(() => isAuthenticated.value && !onboardingComplete.value)
  const authMode: AuthMode = 'api'

  function applyTheme(next: ThemeMode) {
    theme.value = next
    const resolved = next === 'system'
      ? (window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark')
      : next
    document.documentElement.dataset.theme = resolved
    saveValue(THEME_STORAGE_KEY, next)
  }

  function applyLocale(next: Locale) {
    locale.value = next
    document.documentElement.lang = next
    saveValue(LOCALE_STORAGE_KEY, next)
  }

  function onboardingKey() {
    return session.value ? `${ONBOARDING_STORAGE_PREFIX}${session.value.user.id}` : ONBOARDING_STORAGE_PREFIX
  }

  function hydrateLocalPreferences() {
    const storedTheme = savedValue(THEME_STORAGE_KEY)
    applyTheme(storedTheme === 'light' || storedTheme === 'dark' || storedTheme === 'system' ? storedTheme : 'system')
    const storedLocale = savedValue(LOCALE_STORAGE_KEY)
    applyLocale(storedLocale === 'en-US' ? 'en-US' : 'zh-CN')
  }

  async function initialize() {
    if (ready.value) return
    hydrateLocalPreferences()
    const stored = readJson<AuthSession>(AUTH_STORAGE_KEY)
    if (stored?.token && stored.user?.id && stored.user.username) {
      setAuthToken(stored.token)
      try {
        const user = await api.me()
        session.value = { ...stored, user, subject: user.id, displayName: user.username }
        await hydrateAccountSettings()
        const onboarding = savedValue(onboardingKey())
        onboardingComplete.value = onboarding === 'complete'
      } catch {
        setAuthToken(null)
        removeValue(AUTH_STORAGE_KEY)
      }
    }
    ready.value = true
  }

  async function signIn(username: string, password: string, register = false) {
    const normalizedUsername = username.trim().toLowerCase()
    if (!normalizedUsername || password.length < 8) throw new Error('Enter a username and a password of at least 8 characters.')
    const response = register
      ? await api.register({ username: normalizedUsername, password })
      : await api.login({ username: normalizedUsername, password })
    setAuthToken(response.token)
    session.value = {
      subject: response.user.id,
      displayName: response.user.username,
      mode: 'api',
      signedInAt: new Date().toISOString(),
      token: response.token,
      expiresAt: response.expiresAt,
      user: response.user,
    }
    saveValue(AUTH_STORAGE_KEY, JSON.stringify(session.value))
    await hydrateAccountSettings()
    onboardingComplete.value = savedValue(onboardingKey()) === 'complete'
    if (register) {
      onboardingComplete.value = false
      removeValue(onboardingKey())
    }
  }

  function completeOnboarding() {
    onboardingComplete.value = true
    saveValue(onboardingKey(), 'complete')
  }

  function resetOnboarding() {
    onboardingComplete.value = false
    removeValue(onboardingKey())
  }

  async function signOut() {
    try { if (session.value) await api.logout() } catch { /* token is cleared locally even if the server is unavailable */ }
    setAuthToken(null)
    session.value = null
    onboardingComplete.value = false
    removeValue(AUTH_STORAGE_KEY)
  }

  function applySettings(settings: UserSettings) {
    applyTheme(settings.theme)
    applyLocale(settings.locale)
  }

  async function hydrateAccountSettings() {
    try {
      applySettings(await api.settings())
    } catch {
      // Account settings are a preference, so an unavailable settings endpoint
      // must not invalidate an otherwise healthy authentication session.
    }
  }

  return {
    authMode,
    session,
    onboardingComplete,
    theme,
    locale,
    ready,
    isAuthenticated,
    needsOnboarding,
    initialize,
    signIn,
    signOut,
    completeOnboarding,
    resetOnboarding,
    applyTheme,
    applyLocale,
    applySettings,
    hydrateAccountSettings,
  }
})
