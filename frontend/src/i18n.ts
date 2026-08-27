import { computed } from 'vue'
import { useAuthStore } from './stores/auth'

export function localizedText(zh: string, en: string) {
  return typeof document !== 'undefined' && document.documentElement.lang === 'zh-CN' ? zh : en
}

/** Small, explicit product copy helper; the workbench has only two locales. */
export function useLocale() {
  const auth = useAuthStore()
  const isZh = computed(() => auth.locale === 'zh-CN')
  const text = (zh: string, en: string) => isZh.value ? zh : en
  return { auth, isZh, text }
}
