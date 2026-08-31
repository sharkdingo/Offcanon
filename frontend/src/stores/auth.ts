import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, ApiError, type AuthUser, type UserSettings } from '../api'
import { AUTH_UNAUTHORIZED_EVENT } from '../authToken'

export type AuthMode = 'api'
export type ThemeMode = 'dark' | 'light' | 'system'
export type Locale = 'zh-CN' | 'en-US'

export type AuthSession = {
  subject: string
  displayName: string
  mode: AuthMode
  signedInAt: string
  expiresAt: string
  user: AuthUser
}

const AUTH_STORAGE_KEY = 'offcanon.auth.v3'
// Stale browser-only session payloads are removed for security hygiene.
// They are never parsed or used as an authentication fallback.
const STALE_AUTH_STORAGE_KEYS = ['offcanon.auth.v2', 'offcanon.auth.v1']
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
  let unauthorizedListenerInstalled = false
  const isAuthenticated = computed(() => session.value !== null)
  const needsOnboarding = computed(() => isAuthenticated.value && !onboardingComplete.value)
  const authMode: AuthMode = 'api'

  // Account preferences are scoped by user once a session exists. The
  // unscoped keys remain a small pre-login fallback for first paint only.
  function preferenceKey(key: string) {
    return session.value ? `${key}.${session.value.user.id}` : key
  }

  function readPreference(key: string) {
    const scoped = savedValue(preferenceKey(key))
    if (scoped !== null) return scoped
    // Never fall back to another account's unscoped preference after login.
    return session.value ? null : savedValue(key)
  }

  function savePreference(key: string, value: string) {
    saveValue(preferenceKey(key), value)
  }

  function applyTheme(next: ThemeMode, persist = true) {
    theme.value = next
    const resolved = next === 'system'
      ? (window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark')
      : next
    document.documentElement.dataset.theme = resolved
    if (persist) savePreference(THEME_STORAGE_KEY, next)
  }

  function applyLocale(next: Locale, persist = true) {
    locale.value = next
    document.documentElement.lang = next
    if (persist) savePreference(LOCALE_STORAGE_KEY, next)
  }

  function onboardingKey() {
    return session.value ? `${ONBOARDING_STORAGE_PREFIX}${session.value.user.id}` : ONBOARDING_STORAGE_PREFIX
  }

  function hydrateLocalPreferences() {
    const storedTheme = readPreference(THEME_STORAGE_KEY)
    applyTheme(storedTheme === 'light' || storedTheme === 'dark' || storedTheme === 'system' ? storedTheme : 'system', false)
    const storedLocale = readPreference(LOCALE_STORAGE_KEY)
    applyLocale(storedLocale === 'en-US' ? 'en-US' : 'zh-CN', false)
  }

  async function initialize() {
    if (ready.value) return
    if (!unauthorizedListenerInstalled) {
      window.addEventListener(AUTH_UNAUTHORIZED_EVENT, handleUnauthorized)
      unauthorizedListenerInstalled = true
    }
    hydrateLocalPreferences()
    for (const key of STALE_AUTH_STORAGE_KEYS) removeValue(key)
    const stored = readJson<Pick<AuthSession, 'subject' | 'displayName' | 'mode' | 'signedInAt' | 'expiresAt' | 'user'>>(AUTH_STORAGE_KEY)
    try {
      // The HttpOnly cookie is authoritative. Local storage is only a paint
      // cache for timestamps and must never decide whether a cookie session is
      // still valid (it may have been cleared, disabled, or edited).
      const user = await api.me()
      const metadata = stored?.user?.id === user.id && stored.user.username ? stored : null
      const now = new Date().toISOString()
      session.value = {
        subject: user.id,
        displayName: user.username,
        mode: 'api',
        signedInAt: metadata?.signedInAt ?? now,
        // /me intentionally does not expose an expiry. Keep a cached value
        // when available; an empty value is safer than inventing a deadline.
        expiresAt: metadata?.expiresAt ?? '',
        user,
      }
      saveValue(AUTH_STORAGE_KEY, JSON.stringify(session.value))
      hydrateLocalPreferences()
      await hydrateAccountSettings()
      const onboarding = savedValue(onboardingKey())
      onboardingComplete.value = onboarding === 'complete'
    } catch (cause) {
      // A transient backend outage should not turn a still-valid browser
      // session into an apparent sign-out. A confirmed 401, however, must
      // clear the local cache and return to the login screen.
      if (!(cause instanceof ApiError && cause.status === 401)
        && stored?.user?.id && stored.user.username) {
        session.value = { ...stored, mode: 'api' }
        hydrateLocalPreferences()
        onboardingComplete.value = savedValue(onboardingKey()) === 'complete'
      } else {
        removeValue(AUTH_STORAGE_KEY)
      }
    }
    ready.value = true
  }

  function handleUnauthorized(event: Event) {
    session.value = null
    onboardingComplete.value = false
    removeValue(AUTH_STORAGE_KEY)
  }

  async function signIn(username: string, password: string, register = false) {
    const normalizedUsername = username.trim().toLowerCase()
    if (!normalizedUsername || password.length < 8) throw new Error('Enter a username and a password of at least 8 characters.')
    const response = register
      ? await api.register({ username: normalizedUsername, password })
      : await api.login({ username: normalizedUsername, password })
    session.value = {
      subject: response.user.id,
      displayName: response.user.username,
      mode: 'api',
      signedInAt: new Date().toISOString(),
      expiresAt: response.expiresAt,
      user: response.user,
    }
    saveValue(AUTH_STORAGE_KEY, JSON.stringify(session.value))
    hydrateLocalPreferences()
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
    try { if (session.value) await api.logout() } catch { /* local state is cleared even if the server is unavailable */ }
    session.value = null
    onboardingComplete.value = false
    applyTheme('system', false)
    applyLocale('zh-CN', false)
    removeValue(AUTH_STORAGE_KEY)
  }

  function applySettings(settings: UserSettings, persist = true) {
    applyTheme(settings.theme, persist)
    applyLocale(settings.locale, persist)
  }

  function updateSessionExpiry(expiresAt: string) {
    if (!session.value) return
    session.value.expiresAt = expiresAt
    saveValue(AUTH_STORAGE_KEY, JSON.stringify(session.value))
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
    updateSessionExpiry,
    hydrateAccountSettings,
  }
})
