<script setup lang="ts">
import { AlertTriangle, ArrowLeft, Check, CircleUserRound, KeyRound, Languages, LoaderCircle, LogOut, Moon, RefreshCw, RotateCcw, Save, ShieldCheck, Sun, SlidersHorizontal } from 'lucide-vue-next'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, type ModelConfigurationStatus, type ModelTestResponse, type RuntimeSettingsPolicy, type UserSettings } from '../api'
import { useAuthStore, type ThemeMode } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const isZh = computed(() => auth.locale === 'zh-CN')
const initials = computed(() => auth.session?.displayName.slice(0, 2).toUpperCase() ?? 'O')
const loaded = ref(false)
const saving = ref(false)
const saved = ref(false)
const error = ref<string | null>(null)
const modelStatus = ref<ModelConfigurationStatus | null>(null)
const runtimePolicy = ref<RuntimeSettingsPolicy | null>(null)
const modelStatusError = ref<string | null>(null)
const modelTest = ref<ModelTestResponse | null>(null)
const testingModel = ref(false)
const testedModelFingerprint = ref<string | null>(null)
const savedSettings = ref<Pick<UserSettings, 'theme' | 'locale' | 'modelEndpoint' | 'modelName' | 'agentMaxSteps' | 'agentRunTimeoutSeconds' | 'contextLimitChars'> | null>(null)
const form = reactive({
  theme: 'system' as ThemeMode,
  locale: 'zh-CN' as 'zh-CN' | 'en-US',
  modelEndpoint: '',
  modelName: '',
  agentMaxSteps: 20,
  agentRunTimeoutSeconds: 600,
  contextLimitChars: 80000,
})

function modelFingerprint(endpoint: string, model: string) {
  return `${endpoint.trim()}\u0000${model.trim()}`
}

function applyForm(settings: UserSettings, persistAppearance = true) {
  form.theme = settings.theme
  form.locale = settings.locale
  form.modelEndpoint = settings.modelEndpoint
  form.modelName = settings.modelName
  form.agentMaxSteps = settings.agentMaxSteps
  form.agentRunTimeoutSeconds = settings.agentRunTimeoutSeconds
  form.contextLimitChars = settings.contextLimitChars
  savedSettings.value = {
    theme: settings.theme,
    locale: settings.locale,
    modelEndpoint: settings.modelEndpoint,
    modelName: settings.modelName,
    agentMaxSteps: settings.agentMaxSteps,
    agentRunTimeoutSeconds: settings.agentRunTimeoutSeconds,
    contextLimitChars: settings.contextLimitChars,
  }
  auth.applySettings(settings, persistAppearance)
}

async function load() {
  modelStatusError.value = null
  try {
    applyForm(await api.settings())
    loaded.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : (isZh.value ? '无法加载设置。' : 'Unable to load settings.')
  }
  await loadModelStatus()
  await loadRuntimePolicy()
}

async function loadModelStatus() {
  try {
    modelStatus.value = await api.modelStatus()
  } catch (cause) {
    modelStatusError.value = cause instanceof Error
      ? cause.message
      : (isZh.value ? '无法读取模型状态。' : 'Unable to read model status.')
  }
}

async function loadRuntimePolicy() {
  try {
    runtimePolicy.value = await api.runtimePolicy()
  } catch {
    // Runtime policy is advisory UI information; server-side validation remains authoritative.
    runtimePolicy.value = null
  }
}

function setTheme(theme: ThemeMode) {
  form.theme = theme
  auth.applyTheme(theme, false)
  saved.value = false
}

function setLocale(locale: 'zh-CN' | 'en-US') {
  form.locale = locale
  auth.applyLocale(locale, false)
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
    modelTest.value = null
    testedModelFingerprint.value = null
    await loadModelStatus()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : (isZh.value ? '保存失败。' : 'Unable to save settings.')
  } finally {
    saving.value = false
  }
}

async function testModel() {
  testingModel.value = true
  modelTest.value = null
  modelStatusError.value = null
  try {
    modelTest.value = await api.testModel({ modelEndpoint: form.modelEndpoint, modelName: form.modelName })
    testedModelFingerprint.value = modelFingerprint(form.modelEndpoint, form.modelName)
  } catch (cause) {
    modelTest.value = {
      reachable: false,
      code: 'MODEL_CONNECTION_FAILED',
      detail: cause instanceof Error
        ? cause.message
        : (isZh.value ? '模型连接测试失败。' : 'The model connection test failed.'),
    }
  } finally {
    testingModel.value = false
  }
}

const modelReady = computed(() => Boolean(modelStatus.value?.apiKeyConfigured
  && modelStatus.value.effectiveEndpointConfigured
  && modelStatus.value.effectiveModelConfigured
  && modelStatus.value.effectiveEndpointAllowed))

const settingsDirty = computed(() => {
  const baseline = savedSettings.value
  if (!baseline) return false
  return baseline.theme !== form.theme
    || baseline.locale !== form.locale
    || baseline.modelEndpoint !== form.modelEndpoint
    || baseline.modelName !== form.modelName
    || baseline.agentMaxSteps !== form.agentMaxSteps
    || baseline.agentRunTimeoutSeconds !== form.agentRunTimeoutSeconds
    || baseline.contextLimitChars !== form.contextLimitChars
})

const modelDraftChanged = computed(() => {
  const baseline = savedSettings.value
  return !!baseline && modelFingerprint(form.modelEndpoint, form.modelName)
    !== modelFingerprint(baseline.modelEndpoint, baseline.modelName)
})

const modelReadyForDraft = computed(() => modelReady.value && !modelDraftChanged.value)

const modelTestIsCurrent = computed(() => testedModelFingerprint.value === modelFingerprint(form.modelEndpoint, form.modelName))

function resetDraft() {
  const baseline = savedSettings.value
  if (!baseline) return
  form.theme = baseline.theme
  form.locale = baseline.locale
  form.modelEndpoint = baseline.modelEndpoint
  form.modelName = baseline.modelName
  form.agentMaxSteps = baseline.agentMaxSteps
  form.agentRunTimeoutSeconds = baseline.agentRunTimeoutSeconds
  form.contextLimitChars = baseline.contextLimitChars
  auth.applyTheme(form.theme, false)
  auth.applyLocale(form.locale, false)
  modelTest.value = null
  testedModelFingerprint.value = null
  saved.value = false
}

watch(() => [form.modelEndpoint, form.modelName], () => {
  if (!modelTestIsCurrent.value) modelTest.value = null
})

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

function backToWorkspace() {
  const projectId = typeof route.query.projectId === 'string' ? route.query.projectId : ''
  const experimentId = typeof route.query.experimentId === 'string' ? route.query.experimentId : ''
  if (projectId && experimentId) {
    void router.push({ name: 'experiment', params: { projectId, experimentId } })
  } else if (projectId) {
    void router.push({ name: 'project', params: { projectId } })
  } else {
    void router.push({ name: 'home' })
  }
}

onMounted(() => void load())

onBeforeUnmount(() => {
  // A settings draft previews appearance locally, but leaving without saving
  // must not leak that preview into the next screen or account.
  const baseline = savedSettings.value
  if (baseline) {
    auth.applyTheme(baseline.theme, false)
    auth.applyLocale(baseline.locale, false)
  }
})
</script>

<template>
  <div class="settings-shell">
    <header class="settings-topbar">
        <button class="brand-lockup" :aria-label="isZh ? '返回工作区' : 'Back to workspace'" :title="isZh ? '返回工作区' : 'Back to workspace'" @click="backToWorkspace">
        <span class="brand-mark">O</span>
        <span><strong>Offcanon</strong><small>{{ isZh ? '项目列表' : 'project list' }}</small></span>
      </button>
      <div class="settings-topbar-actions">
        <button class="icon-button" :aria-label="isZh ? '切换语言' : 'Switch language'" :title="isZh ? 'English' : '中文'" @click="toggleLocale"><Languages :size="16" /></button>
        <button class="icon-button" :aria-label="isZh ? '返回工作区' : 'Back to workspace'" :title="isZh ? '返回工作区' : 'Back to workspace'" @click="backToWorkspace"><ArrowLeft :size="17" /></button>
      </div>
    </header>

    <main class="settings-main" aria-labelledby="settings-title">
      <header class="settings-heading">
        <div>
          <p class="eyebrow">{{ isZh ? '账户 / 偏好' : 'ACCOUNT / PREFERENCES' }}</p>
          <h1 id="settings-title">{{ isZh ? '设置' : 'Settings' }}</h1>
          <p>{{ isZh ? '管理账户偏好与实验运行默认值；项目验收规则在项目设置中维护。' : 'Manage account preferences and run defaults; project acceptance rules live with each project.' }}</p>
        </div>
        <span class="settings-avatar" aria-hidden="true">{{ initials }}</span>
      </header>

      <p v-if="error" class="global-alert" role="alert">{{ error }}</p>
      <p v-if="saved" class="settings-saved" role="status"><Check :size="15" />{{ isZh ? '已保存' : 'Saved' }}</p>
      <p v-else-if="settingsDirty" class="settings-draft-notice" role="status">{{ isZh ? '有未保存的设置草稿' : 'You have unsaved settings' }}</p>

      <section class="settings-section" aria-labelledby="profile-heading">
        <div class="settings-section-heading"><CircleUserRound :size="17" /><div><h2 id="profile-heading">{{ isZh ? '账户' : 'Account' }}</h2></div></div>
        <dl class="settings-details">
          <div><dt>{{ isZh ? '用户名' : 'Username' }}</dt><dd>{{ auth.session?.user.username }}</dd></div>
        </dl>
      </section>

      <form @submit.prevent="save">
      <section class="settings-section" aria-labelledby="appearance-heading">
        <div class="settings-section-heading"><Sun :size="17" /><div><h2 id="appearance-heading">{{ isZh ? '外观与语言' : 'Appearance & language' }}</h2></div></div>
        <div class="settings-control-row">
          <span class="settings-control-label">{{ isZh ? '主题' : 'Theme' }}</span>
          <div class="segmented-control" role="group" :aria-label="isZh ? '主题' : 'Theme'">
            <button type="button" :aria-pressed="form.theme === 'system'" @click="setTheme('system')">{{ isZh ? '跟随系统' : 'System' }} <Check v-if="form.theme === 'system'" :size="13" /></button>
            <button type="button" :aria-pressed="form.theme === 'dark'" @click="setTheme('dark')"><Moon :size="14" /> {{ isZh ? '深色' : 'Dark' }} <Check v-if="form.theme === 'dark'" :size="13" /></button>
            <button type="button" :aria-pressed="form.theme === 'light'" @click="setTheme('light')"><Sun :size="14" /> {{ isZh ? '浅色' : 'Light' }} <Check v-if="form.theme === 'light'" :size="13" /></button>
          </div>
        </div>
        <div class="settings-control-row">
          <span class="settings-control-label">{{ isZh ? '语言' : 'Language' }}</span>
          <div class="segmented-control" role="group" :aria-label="isZh ? '语言' : 'Language'">
            <button type="button" :aria-pressed="form.locale === 'zh-CN'" @click="setLocale('zh-CN')">中文 <Check v-if="form.locale === 'zh-CN'" :size="13" /></button>
            <button type="button" :aria-pressed="form.locale === 'en-US'" @click="setLocale('en-US')">English <Check v-if="form.locale === 'en-US'" :size="13" /></button>
          </div>
        </div>
      </section>

      <section class="settings-section" aria-labelledby="runtime-heading">
        <div class="settings-section-heading"><SlidersHorizontal :size="17" /><div><h2 id="runtime-heading">{{ isZh ? '运行默认值' : 'Run defaults' }}</h2><p>{{ isZh ? '用于新实验；服务端资源上限始终优先。' : 'Applied to new experiments; deployment ceilings always take precedence.' }}</p></div></div>
        <div class="settings-form-grid">
          <label><span>{{ isZh ? '最大步数' : 'Max steps' }}</span><input v-model.number="form.agentMaxSteps" type="number" min="1" :max="runtimePolicy?.maxStepsCeiling ?? 100" required /></label>
          <label><span>{{ isZh ? '运行超时（秒）' : 'Run timeout (seconds)' }}</span><input v-model.number="form.agentRunTimeoutSeconds" type="number" min="10" :max="runtimePolicy?.runTimeoutSecondsCeiling ?? 86400" required /></label>
          <label><span>{{ isZh ? '上下文上限（字符）' : 'Context limit (characters)' }}</span><input v-model.number="form.contextLimitChars" type="number" min="8000" :max="runtimePolicy?.contextLimitCharsCeiling ?? 1000000" required /></label>
        </div>
        <p v-if="runtimePolicy" class="field-help runtime-policy-help">{{ isZh ? `服务端上限：${runtimePolicy.maxStepsCeiling} 步 / ${runtimePolicy.runTimeoutSecondsCeiling} 秒 / ${runtimePolicy.contextLimitCharsCeiling} 字符。` : `Deployment ceilings: ${runtimePolicy.maxStepsCeiling} steps / ${runtimePolicy.runTimeoutSecondsCeiling}s / ${runtimePolicy.contextLimitCharsCeiling} chars.` }}</p>
      </section>

      <section class="settings-section" aria-labelledby="model-heading">
        <div class="settings-section-heading"><KeyRound :size="17" /><div><h2 id="model-heading">{{ isZh ? '模型连接偏好' : 'Model connection preference' }}</h2><p>{{ isZh ? 'Endpoint 和模型名是账户级选择；API key、allowlist 和请求策略只由服务端管理。' : 'Endpoint and model are account-level choices; the API key, allowlist, and request policy are server-managed.' }}</p></div></div>
        <div class="settings-form-grid model-grid">
          <label><span>Endpoint <small>{{ isZh ? '模型服务地址' : 'model service address' }}</small></span><input v-model.trim="form.modelEndpoint" type="url" autocomplete="url" placeholder="https://provider.example/v1" /></label>
          <label><span>Model <small>{{ isZh ? '要使用的模型标识' : 'model identifier to use' }}</small></span><input v-model.trim="form.modelName" autocomplete="off" placeholder="provider-model-id" maxlength="200" /></label>
        </div>
        <p class="field-help model-input-help">{{ isZh ? '留空表示使用服务端默认值；自定义地址必须在服务端允许列表中。API key 始终由服务端保管。' : 'Leave a field blank to use the deployment default; a custom endpoint must be on the server allowlist. The API key is always kept by the server.' }}</p>
        <div v-if="modelStatus?.allowedEndpoints?.length" class="allowed-endpoint-list" :aria-label="isZh ? '服务端允许的模型地址' : 'Server-allowed model endpoints'">
          <span>{{ isZh ? '允许使用' : 'Allowed' }}</span>
          <button v-for="endpoint in modelStatus.allowedEndpoints" :key="endpoint" type="button" :title="endpoint" @click="form.modelEndpoint = endpoint">{{ endpoint }}</button>
        </div>
        <dl v-if="modelStatus" class="settings-details model-effective-details">
          <div><dt>{{ isZh ? '当前生效 Endpoint' : 'Effective endpoint' }}</dt><dd><code>{{ modelStatus.effectiveEndpoint || (isZh ? '未配置' : 'Not configured') }}</code></dd></div>
          <div><dt>{{ isZh ? '当前生效模型' : 'Effective model' }}</dt><dd><code>{{ modelStatus.effectiveModel || (isZh ? '未配置' : 'Not configured') }}</code></dd></div>
          <div><dt>{{ isZh ? '允许的 Endpoint 数' : 'Allowed endpoints' }}</dt><dd>{{ modelStatus.allowedEndpointCount }}</dd></div>
        </dl>
        <div class="settings-gate" :class="{ ready: modelReadyForDraft, error: modelStatusError || (modelTest && !modelTest.reachable) }" role="status">
          <ShieldCheck v-if="modelReadyForDraft" :size="17" />
          <AlertTriangle v-else :size="17" />
          <div>
            <strong>{{ modelReadyForDraft ? (isZh ? '模型配置可用' : 'Model configuration is ready') : (isZh ? '模型配置尚未就绪' : 'Model configuration needs attention') }}</strong>
            <span v-if="modelStatusError">{{ modelStatusError }}</span>
            <span v-else-if="!modelStatus">{{ isZh ? '正在读取服务端配置。' : 'Reading server configuration.' }}</span>
            <span v-else-if="!modelStatus.apiKeyConfigured">{{ isZh ? '服务端尚未配置 OFFCANON_MODEL_API_KEY。' : 'The server has no OFFCANON_MODEL_API_KEY configured.' }}</span>
            <span v-else-if="!modelStatus.effectiveEndpointConfigured || !modelStatus.effectiveModelConfigured">{{ isZh ? '请填写 endpoint 和模型名，或配置服务端默认值。' : 'Set an endpoint and model name, or configure deployment defaults.' }}</span>
            <span v-else-if="!modelStatus.effectiveEndpointAllowed">{{ isZh ? '当前 endpoint 不在服务端 allowlist 中。' : 'The selected endpoint is outside the server allowlist.' }}</span>
            <span v-else-if="modelDraftChanged">{{ isZh ? '草稿已变化，请保存后再用于新实验。' : 'The draft changed; save it before using it for new experiments.' }}</span>
            <span v-else>{{ isZh ? 'API key 不会发送到浏览器或保存到账户。' : 'The API key never enters the browser or account storage.' }}</span>
          </div>
          <span class="status-badge" :class="modelReadyForDraft ? 'success' : 'warning'">{{ modelReadyForDraft ? (isZh ? '可运行' : 'READY') : (isZh ? '需配置' : 'CHECK CONFIG') }}</span>
        </div>
        <p v-if="modelDraftChanged" class="field-help model-draft-help">{{ isZh ? '模型草稿尚未保存；当前生效状态仍以上方已保存配置为准。' : 'The model draft is not saved; the effective status above still reflects the saved configuration.' }}</p>
        <div class="settings-model-actions">
          <button type="button" class="button secondary" :disabled="testingModel || !loaded" @click="testModel">
            <LoaderCircle v-if="testingModel" class="spin" :size="15" /><RefreshCw v-else :size="15" />
            {{ testingModel ? (isZh ? '测试中...' : 'Testing...') : (isZh ? '测试模型连接' : 'Test model connection') }}
          </button>
          <span v-if="modelTest && modelTestIsCurrent" class="settings-model-result" :class="modelTest.reachable ? 'success' : 'error'" role="status">{{ modelTest.reachable ? (isZh ? '当前草稿连接成功；保存后用于新实验。' : 'Draft connection succeeded; save to use it for new experiments.') : `${modelTest.code}: ${modelTest.detail}` }}</span>
          <span v-else-if="modelTest" class="settings-model-result error" role="status">{{ isZh ? '模型字段已变化，请重新测试。' : 'Model fields changed; test again.' }}</span>
        </div>
      </section>

      <div class="settings-save-row"><button type="button" class="button secondary" :disabled="saving || !loaded || !settingsDirty" @click="resetDraft"><RotateCcw :size="15" />{{ isZh ? '撤销草稿' : 'Discard draft' }}</button><button type="submit" class="button primary" :disabled="saving || !loaded || !settingsDirty"><Save :size="15" />{{ saving ? (isZh ? '保存中...' : 'Saving...') : (isZh ? '保存设置' : 'Save settings') }}</button></div>
      </form>

      <section class="settings-section settings-actions" aria-labelledby="first-run-heading">
        <div class="settings-section-heading"><RotateCcw :size="17" /><div><h2 id="first-run-heading">{{ isZh ? '首次使用' : 'First run' }}</h2><p>{{ isZh ? '重新显示首次使用引导。' : 'Show the first-run guide again.' }}</p></div></div>
        <div class="settings-action-row">
          <button class="button secondary" @click="resetOnboarding"><RotateCcw :size="15" /> {{ isZh ? '重新查看引导' : 'Show first-run guide' }}</button>
          <button class="button danger-ghost" @click="signOut"><LogOut :size="15" /> {{ isZh ? '退出登录' : 'Sign out' }}</button>
        </div>
      </section>
    </main>
  </div>
</template>
