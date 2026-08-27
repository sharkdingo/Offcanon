<script setup lang="ts">
import { ArrowLeft, Check, CircleUserRound, Languages, LogOut, Moon, RotateCcw, Save, Sun, SlidersHorizontal } from 'lucide-vue-next'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type UserSettings } from '../api'
import { useAuthStore, type ThemeMode } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const isZh = computed(() => auth.locale === 'zh-CN')
const initials = computed(() => auth.session?.displayName.slice(0, 2).toUpperCase() ?? 'O')
const loaded = ref(false)
const saving = ref(false)
const saved = ref(false)
const error = ref<string | null>(null)
const form = reactive({
  theme: 'system' as ThemeMode,
  locale: 'zh-CN' as 'zh-CN' | 'en-US',
  modelEndpoint: '',
  modelName: '',
  agentMaxSteps: 20,
  agentRunTimeoutSeconds: 600,
  contextLimitChars: 80000,
})

function applyForm(settings: UserSettings) {
  form.theme = settings.theme
  form.locale = settings.locale
  form.modelEndpoint = settings.modelEndpoint
  form.modelName = settings.modelName
  form.agentMaxSteps = settings.agentMaxSteps
  form.agentRunTimeoutSeconds = settings.agentRunTimeoutSeconds
  form.contextLimitChars = settings.contextLimitChars
  auth.applySettings(settings)
}

async function load() {
  try {
    applyForm(await api.settings())
    loaded.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : (isZh.value ? '无法加载设置。' : 'Unable to load settings.')
  }
}

function setTheme(theme: ThemeMode) {
  form.theme = theme
  auth.applyTheme(theme)
  saved.value = false
}

function setLocale(locale: 'zh-CN' | 'en-US') {
  form.locale = locale
  auth.applyLocale(locale)
  saved.value = false
}

async function save() {
  saving.value = true
  saved.value = false
  error.value = null
  try {
    const settings = await api.updateSettings({ ...form })
    applyForm(settings)
    saved.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : (isZh.value ? '保存失败。' : 'Unable to save settings.')
  } finally {
    saving.value = false
  }
}

function resetOnboarding() {
  auth.resetOnboarding()
  void router.replace({ name: 'home' })
}

async function signOut() {
  await auth.signOut()
  await router.replace({ name: 'home' })
}

function toggleLocale() {
  setLocale(isZh.value ? 'en-US' : 'zh-CN')
}

onMounted(() => void load())
</script>

<template>
  <div class="settings-shell">
    <header class="settings-topbar">
      <button class="brand-lockup" :aria-label="isZh ? '返回工作区' : 'Back to workspace'" @click="router.push({ name: 'home' })">
        <span class="brand-mark">O</span>
        <span><strong>Offcanon</strong><small>{{ isZh ? '变更工作区' : 'change workspace' }}</small></span>
      </button>
      <div class="settings-topbar-actions">
        <button class="icon-button" :aria-label="isZh ? '切换语言' : 'Switch language'" :title="isZh ? 'English' : '中文'" @click="toggleLocale"><Languages :size="16" /></button>
        <button class="icon-button" :aria-label="isZh ? '返回工作区' : 'Back to workspace'" title="Back" @click="router.push({ name: 'home' })"><ArrowLeft :size="17" /></button>
      </div>
    </header>

    <main class="settings-main" aria-labelledby="settings-title">
      <header class="settings-heading">
        <div>
          <p class="eyebrow">{{ isZh ? '账户 / 偏好' : 'ACCOUNT / PREFERENCES' }}</p>
          <h1 id="settings-title">{{ isZh ? '设置' : 'Settings' }}</h1>
          <p>{{ isZh ? '偏好会保存到你的账户，并影响之后的实验运行。' : 'Preferences are saved to your account and shape future experiment runs.' }}</p>
        </div>
        <span class="settings-avatar" aria-hidden="true">{{ initials }}</span>
      </header>

      <p v-if="error" class="global-alert" role="alert">{{ error }}</p>
      <p v-if="saved" class="settings-saved" role="status"><Check :size="15" />{{ isZh ? '已保存' : 'Saved' }}</p>

      <section class="settings-section" aria-labelledby="profile-heading">
        <div class="settings-section-heading"><CircleUserRound :size="17" /><div><h2 id="profile-heading">{{ isZh ? '账户' : 'Account' }}</h2><p>{{ isZh ? '当前登录身份' : 'Authenticated identity' }}</p></div></div>
        <dl class="settings-details">
          <div><dt>{{ isZh ? '用户名' : 'Username' }}</dt><dd>{{ auth.session?.user.username }}</dd></div>
          <div><dt>{{ isZh ? '会话' : 'Session' }}</dt><dd><span class="status-badge verified">{{ isZh ? '已登录' : 'SIGNED IN' }}</span></dd></div>
        </dl>
      </section>

      <section class="settings-section" aria-labelledby="appearance-heading">
        <div class="settings-section-heading"><Sun :size="17" /><div><h2 id="appearance-heading">{{ isZh ? '外观与语言' : 'Appearance & language' }}</h2><p>{{ isZh ? '偏好跨设备保存。' : 'These preferences are stored with your account.' }}</p></div></div>
        <div class="settings-control-row">
          <span class="settings-control-label">{{ isZh ? '主题' : 'Theme' }}</span>
          <div class="segmented-control" role="group" :aria-label="isZh ? '主题' : 'Theme'">
            <button :aria-pressed="form.theme === 'system'" @click="setTheme('system')">System <Check v-if="form.theme === 'system'" :size="13" /></button>
            <button :aria-pressed="form.theme === 'dark'" @click="setTheme('dark')"><Moon :size="14" /> Dark <Check v-if="form.theme === 'dark'" :size="13" /></button>
            <button :aria-pressed="form.theme === 'light'" @click="setTheme('light')"><Sun :size="14" /> Light <Check v-if="form.theme === 'light'" :size="13" /></button>
          </div>
        </div>
        <div class="settings-control-row">
          <span class="settings-control-label">{{ isZh ? '语言' : 'Language' }}</span>
          <div class="segmented-control" role="group" :aria-label="isZh ? '语言' : 'Language'">
            <button :aria-pressed="form.locale === 'zh-CN'" @click="setLocale('zh-CN')">中文 <Check v-if="form.locale === 'zh-CN'" :size="13" /></button>
            <button :aria-pressed="form.locale === 'en-US'" @click="setLocale('en-US')">English <Check v-if="form.locale === 'en-US'" :size="13" /></button>
          </div>
        </div>
      </section>

      <section class="settings-section" aria-labelledby="runtime-heading">
        <div class="settings-section-heading"><SlidersHorizontal :size="17" /><div><h2 id="runtime-heading">{{ isZh ? '运行策略' : 'Run policy' }}</h2><p>{{ isZh ? '这些限制会在每次实验启动时生效。' : 'These limits are applied when each experiment starts.' }}</p></div></div>
        <div class="settings-form-grid">
          <label><span>{{ isZh ? '最大步数' : 'Max steps' }}</span><input v-model.number="form.agentMaxSteps" type="number" min="1" max="100" /></label>
          <label><span>{{ isZh ? '运行超时（秒）' : 'Run timeout (seconds)' }}</span><input v-model.number="form.agentRunTimeoutSeconds" type="number" min="10" max="86400" /></label>
          <label><span>{{ isZh ? '上下文上限（字符）' : 'Context limit (characters)' }}</span><input v-model.number="form.contextLimitChars" type="number" min="8000" max="1000000" /></label>
        </div>
      </section>

      <section class="settings-section" aria-labelledby="model-heading">
        <div class="settings-section-heading"><CircleUserRound :size="17" /><div><h2 id="model-heading">{{ isZh ? '模型连接' : 'Model connection' }}</h2><p>{{ isZh ? '只保存 endpoint 和模型名；API key 仅存在服务端环境。' : 'Only the endpoint and model name are stored; API keys stay in the server environment.' }}</p></div></div>
        <div class="settings-form-grid model-grid">
          <label><span>Endpoint</span><input v-model="form.modelEndpoint" type="url" placeholder="https://provider.example/v1" /></label>
          <label><span>Model</span><input v-model="form.modelName" placeholder="provider-model-id" maxlength="200" /></label>
        </div>
      </section>

      <div class="settings-save-row"><button class="button primary" :disabled="saving || !loaded" @click="save"><Save :size="15" />{{ saving ? (isZh ? '保存中...' : 'Saving...') : (isZh ? '保存设置' : 'Save settings') }}</button></div>

      <section class="settings-section settings-actions" aria-labelledby="workspace-heading">
        <div class="settings-section-heading"><RotateCcw :size="17" /><div><h2 id="workspace-heading">{{ isZh ? '工作区' : 'Workspace' }}</h2><p>{{ isZh ? '仅重置首次使用引导。' : 'Reset only the first-run guide.' }}</p></div></div>
        <div class="settings-action-row">
          <button class="button secondary" @click="resetOnboarding"><RotateCcw :size="15" /> {{ isZh ? '重新查看引导' : 'Show first-run guide' }}</button>
          <button class="button danger-ghost" @click="signOut"><LogOut :size="15" /> {{ isZh ? '退出登录' : 'Sign out' }}</button>
        </div>
      </section>
    </main>
  </div>
</template>
