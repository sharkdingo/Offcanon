<script setup lang="ts">
import { AlertTriangle, ArrowLeft, Check, CircleUserRound, Database, Download, KeyRound, Languages, LoaderCircle, LogOut, Moon, RefreshCw, RotateCcw, Save, ShieldCheck, Sun, SlidersHorizontal, Trash2 } from 'lucide-vue-next'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { api, type ModelConfigurationStatus, type ModelTestResponse, type RuntimeSettingsPolicy, type StorageSummary, type UserSettings } from '../api'
import { useAuthStore, type ThemeMode } from '../stores/auth'
import BaseDialog from '../components/BaseDialog.vue'
import { formatCode, formatError } from '../ui'

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
const modelApiKeyDraft = ref('')
const modelApiKeyConfigured = ref(false)
const modelApiKeySaving = ref(false)
const storage = ref<StorageSummary | null>(null)
const storageLoading = ref(false)
const storageBusy = ref(false)
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const passwordSaving = ref(false)
const passwordMessage = ref<string | null>(null)
const showLeaveConfirm = ref(false)
const showCleanupConfirm = ref(false)
let pendingRoute: { path: string; replace?: boolean } | null = null
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
  return `${endpoint.trim()}\u0000${model.trim()}\u0000${modelApiKeyConfigured.value ? 'configured' : 'missing'}\u0000${modelApiKeyDraft.value ? 'draft' : 'saved'}`
}

function applyForm(settings: UserSettings, persistAppearance = true) {
  form.theme = settings.theme
  form.locale = settings.locale
  form.modelEndpoint = settings.modelEndpoint
  form.modelName = settings.modelName
  form.agentMaxSteps = settings.agentMaxSteps
  form.agentRunTimeoutSeconds = settings.agentRunTimeoutSeconds
  form.contextLimitChars = settings.contextLimitChars
  modelApiKeyConfigured.value = settings.modelApiKeyConfigured
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
    error.value = formatError(cause, '无法加载设置。', 'Unable to load settings.')
  }
  await loadModelStatus()
  await loadRuntimePolicy()
}

async function loadModelStatus() {
  try {
    modelStatus.value = await api.modelStatus()
  } catch (cause) {
    modelStatusError.value = formatError(cause, '无法读取模型状态。', 'Unable to read model status.')
  }
}

async function loadRuntimePolicy() {
  try {
    runtimePolicy.value = await api.runtimePolicy()
  } catch {
    // Runtime policy is advisory UI information; backend validation remains authoritative.
    runtimePolicy.value = null
  }
}

async function loadStorage() {
  storageLoading.value = true
  try {
    storage.value = await api.storageSummary()
  } catch (cause) {
    error.value = formatError(cause, '无法读取数据概览。', 'Unable to read the data summary.')
  } finally {
    storageLoading.value = false
  }
}

async function changePassword() {
  passwordMessage.value = null
  if (passwordForm.newPassword.length < 8 || passwordForm.newPassword !== passwordForm.confirmPassword) {
    passwordMessage.value = isZh.value ? '新密码至少需要 8 位，且两次输入必须一致。' : 'The new password must be at least 8 characters and match its confirmation.'
    return
  }
  passwordSaving.value = true
  error.value = null
  try {
    const response = await api.changePassword({ currentPassword: passwordForm.currentPassword, newPassword: passwordForm.newPassword })
    auth.updateSessionExpiry(response.expiresAt)
    passwordForm.currentPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordMessage.value = isZh.value ? '密码已更新，其他设备的登录已失效。' : 'Password updated; other sessions were signed out.'
  } catch (cause) {
    passwordMessage.value = formatError(cause, '无法更新密码，请检查当前密码。', 'Unable to update the password. Check the current password.')
  } finally {
    passwordSaving.value = false
  }
}

function requestCleanupRuntime() {
  if (storageBusy.value) return
  showCleanupConfirm.value = true
}

async function cleanupRuntime() {
  showCleanupConfirm.value = false
  if (storageBusy.value) return
  storageBusy.value = true
  error.value = null
  try {
    await api.cleanupRuntime()
    await loadStorage()
  } catch (cause) {
    error.value = formatError(cause, '无法清理可重建运行文件。', 'Unable to clean rebuildable runtime files.')
  } finally {
    storageBusy.value = false
  }
}

async function exportData() {
  storageBusy.value = true
  error.value = null
  try {
    const payload = await api.exportData()
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'offcanon-export-' + new Date().toISOString().slice(0, 10) + '.json'
    link.click()
    URL.revokeObjectURL(url)
  } catch (cause) {
    error.value = formatError(cause, '无法导出数据。', 'Unable to export data.')
  } finally {
    storageBusy.value = false
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
    const settings = await api.updateSettings({ ...form, modelApiKey: modelApiKeyDraft.value.trim() || undefined })
    applyForm(settings)
    modelApiKeyDraft.value = ''
    saved.value = true
    modelTest.value = null
    testedModelFingerprint.value = null
    await loadModelStatus()
  } catch (cause) {
    error.value = formatError(cause, '保存设置失败。', 'Unable to save settings.')
  } finally {
    saving.value = false
  }
}

async function testModel() {
  testingModel.value = true
  modelTest.value = null
  modelStatusError.value = null
  try {
    modelTest.value = await api.testModel({
      modelEndpoint: form.modelEndpoint,
      modelName: form.modelName,
      apiKey: modelApiKeyDraft.value.trim() || undefined,
    })
    testedModelFingerprint.value = modelFingerprint(form.modelEndpoint, form.modelName)
  } catch (cause) {
    modelTest.value = {
      reachable: false,
      code: 'MODEL_CONNECTION_FAILED',
      detail: formatError(cause, '模型连接测试失败。', 'The model connection test failed.'),
    }
  } finally {
    testingModel.value = false
  }
}

async function clearModelApiKey() {
  if (!modelApiKeyConfigured.value || modelApiKeySaving.value || saving.value) return
  modelApiKeySaving.value = true
  error.value = null
  try {
    const updated = await api.clearModelCredential()
    modelApiKeyConfigured.value = updated.modelApiKeyConfigured
    modelApiKeyDraft.value = ''
    modelTest.value = null
    testedModelFingerprint.value = null
    await loadModelStatus()
  } catch (cause) {
    error.value = formatError(cause, '无法清除模型密钥。', 'Unable to clear model key.')
  } finally {
    modelApiKeySaving.value = false
  }
}

const modelReady = computed(() => Boolean(modelStatus.value?.apiKeyConfigured
  && modelStatus.value.endpointConfigured
  && modelStatus.value.modelConfigured
  && modelStatus.value.endpointValid))

const settingsDirty = computed(() => {
  const baseline = savedSettings.value
  if (!baseline) return Boolean(modelApiKeyDraft.value.trim())
  return baseline.theme !== form.theme
    || baseline.locale !== form.locale
    || baseline.modelEndpoint !== form.modelEndpoint
    || baseline.modelName !== form.modelName
    || baseline.agentMaxSteps !== form.agentMaxSteps
    || baseline.agentRunTimeoutSeconds !== form.agentRunTimeoutSeconds
    || baseline.contextLimitChars !== form.contextLimitChars
    || Boolean(modelApiKeyDraft.value.trim())
})

const modelDraftChanged = computed(() => {
  const baseline = savedSettings.value
  return Boolean(modelApiKeyDraft.value.trim()) || (!!baseline
    && (baseline.modelEndpoint !== form.modelEndpoint || baseline.modelName !== form.modelName))
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
  modelApiKeyDraft.value = ''
  auth.applyTheme(form.theme, false)
  auth.applyLocale(form.locale, false)
  modelTest.value = null
  testedModelFingerprint.value = null
  saved.value = false
}

watch(() => [form.modelEndpoint, form.modelName, modelApiKeyDraft.value], () => {
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
  if (settingsDirty.value) {
    showLeaveConfirm.value = true
    pendingRoute = null
    return
  }
  void router.push(workspaceTarget())
}

function workspaceTarget() {
  const projectId = typeof route.query.projectId === 'string' ? route.query.projectId : ''
  const experimentId = typeof route.query.experimentId === 'string' ? route.query.experimentId : ''
  if (projectId && experimentId) return { name: 'experiment', params: { projectId, experimentId } } as const
  if (projectId) return { name: 'project', params: { projectId } } as const
  return { name: 'home' } as const
}

function discardAndLeave() {
  const target = pendingRoute
  pendingRoute = null
  showLeaveConfirm.value = false
  resetDraft()
  void router.push(target?.path ?? router.resolve(workspaceTarget()).fullPath)
}

function saveAndLeave() {
  const target = pendingRoute
  pendingRoute = null
  showLeaveConfirm.value = false
  void save().then(() => {
    if (!error.value) void router.push(target?.path ?? router.resolve(workspaceTarget()).fullPath)
    else showLeaveConfirm.value = true
  })
}

onBeforeRouteLeave((to) => {
  if (!settingsDirty.value || showLeaveConfirm.value) return true
  showLeaveConfirm.value = true
  pendingRoute = { path: to.fullPath }
  return false
})

function leaveToPendingRoute() {
  discardAndLeave()
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!settingsDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => {
  void load()
  void loadStorage()
  window.addEventListener('beforeunload', handleBeforeUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
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
        <div class="settings-section-heading"><SlidersHorizontal :size="17" /><div><h2 id="runtime-heading">{{ isZh ? '运行默认值' : 'Run defaults' }}</h2><p>{{ isZh ? '用于新实验；应用安全上限始终优先。' : 'Applied to new experiments; application safety limits always take precedence.' }}</p></div></div>
        <div class="settings-form-grid">
          <label><span>{{ isZh ? '最大步数' : 'Max steps' }}</span><input v-model.number="form.agentMaxSteps" type="number" min="1" :max="runtimePolicy?.maxStepsCeiling ?? 100" required /></label>
          <label><span>{{ isZh ? '运行超时（秒）' : 'Run timeout (seconds)' }}</span><input v-model.number="form.agentRunTimeoutSeconds" type="number" min="10" :max="runtimePolicy?.runTimeoutSecondsCeiling ?? 86400" required /></label>
          <label><span>{{ isZh ? '上下文上限（字符）' : 'Context limit (characters)' }}</span><input v-model.number="form.contextLimitChars" type="number" min="8000" :max="runtimePolicy?.contextLimitCharsCeiling ?? 1000000" required /></label>
        </div>
        <p v-if="runtimePolicy" class="field-help runtime-policy-help">{{ isZh ? `应用安全上限：${runtimePolicy.maxStepsCeiling} 步 / ${runtimePolicy.runTimeoutSecondsCeiling} 秒 / ${runtimePolicy.contextLimitCharsCeiling} 字符。` : `Application safety limits: ${runtimePolicy.maxStepsCeiling} steps / ${runtimePolicy.runTimeoutSecondsCeiling}s / ${runtimePolicy.contextLimitCharsCeiling} chars.` }}</p>
      </section>

      <section class="settings-section" aria-labelledby="model-heading">
        <div class="settings-section-heading"><KeyRound :size="17" /><div><h2 id="model-heading">{{ isZh ? '模型连接' : 'Model connection' }}</h2><p>{{ isZh ? '使用 OpenAI 兼容的 Chat Completions 服务；Endpoint、模型名和 API key 都保存在当前账户中。' : 'Use an OpenAI-compatible Chat Completions service; the endpoint, model name, and API key are saved to this account.' }}</p></div></div>
        <div class="settings-form-grid model-grid">
          <label><span>{{ isZh ? 'OpenAI 兼容 Endpoint' : 'OpenAI-compatible Endpoint' }} <small>{{ isZh ? '服务端会请求 /chat/completions' : 'the server calls /chat/completions' }}</small></span><input v-model.trim="form.modelEndpoint" type="url" autocomplete="url" placeholder="https://provider.example/v1" /></label>
          <label><span>Model <small>{{ isZh ? '要使用的模型标识' : 'model identifier to use' }}</small></span><input v-model.trim="form.modelName" autocomplete="off" placeholder="provider-model-id" maxlength="200" /></label>
          <label><span>API key <small>{{ isZh ? '仅显示输入框，不会回显已保存密钥' : 'saved securely; never echoed back' }}</small></span><input v-model="modelApiKeyDraft" type="password" autocomplete="new-password" :placeholder="modelApiKeyConfigured ? (isZh ? '已配置，输入新密钥可替换' : 'Configured; enter a new key to replace') : (isZh ? '输入服务商密钥' : 'Enter provider key')" /></label>
        </div>
        <p class="field-help model-input-help">{{ isZh ? 'Endpoint 必须是 HTTP(S) 基础地址，不能包含凭据、查询参数或片段；服务端会向它追加 /chat/completions。API key 仅在后端加密保存，并只发送到你选择的 Endpoint。' : 'Use an HTTP(S) base URL without credentials, query parameters, or fragments; the server appends /chat/completions. The API key is encrypted on the server and sent only to your selected endpoint.' }}</p>
        <div class="settings-model-actions">
          <button type="button" class="button danger-ghost" :disabled="!modelApiKeyConfigured || modelApiKeySaving || saving" @click="clearModelApiKey"><Trash2 :size="15" />{{ isZh ? '清除 API key' : 'Clear API key' }}</button>
        </div>
        <dl v-if="modelStatus" class="settings-details model-effective-details">
          <div><dt>{{ isZh ? '当前生效 Endpoint' : 'Effective endpoint' }}</dt><dd><code>{{ modelStatus.endpoint || (isZh ? '未配置' : 'Not configured') }}</code></dd></div>
          <div><dt>{{ isZh ? '当前生效模型' : 'Effective model' }}</dt><dd><code>{{ modelStatus.model || (isZh ? '未配置' : 'Not configured') }}</code></dd></div>
          <div><dt>{{ isZh ? '密钥状态' : 'API key' }}</dt><dd>{{ modelStatus.apiKeyConfigured ? (isZh ? '已配置' : 'Configured') : (isZh ? '未配置' : 'Not configured') }}</dd></div>
        </dl>
        <div class="settings-gate" :class="{ ready: modelReadyForDraft, error: modelStatusError || (modelTest && !modelTest.reachable) }" role="status">
          <ShieldCheck v-if="modelReadyForDraft" :size="17" />
          <AlertTriangle v-else :size="17" />
          <div>
            <strong>{{ modelReadyForDraft ? (isZh ? '模型配置可用' : 'Model configuration is ready') : (isZh ? '模型配置尚未就绪' : 'Model configuration needs attention') }}</strong>
            <span v-if="modelStatusError">{{ modelStatusError }}</span>
            <span v-else-if="!modelStatus">{{ isZh ? '正在读取服务端配置。' : 'Reading server configuration.' }}</span>
            <span v-else-if="!modelStatus.apiKeyConfigured">{{ isZh ? '请在此处保存模型 API key。' : 'Save a model API key here.' }}</span>
            <span v-else-if="!modelStatus.endpointConfigured || !modelStatus.modelConfigured">{{ isZh ? '请填写 endpoint 和模型名。' : 'Set an endpoint and model name.' }}</span>
            <span v-else-if="!modelStatus.endpointValid">{{ isZh ? '当前 endpoint 不是有效的 HTTP(S) 地址。' : 'The selected endpoint is not a valid HTTP(S) URL.' }}</span>
            <span v-else-if="modelDraftChanged">{{ isZh ? '草稿已变化，请保存后再用于新实验。' : 'The draft changed; save it before using it for new experiments.' }}</span>
            <span v-else>{{ isZh ? '模型配置保存在账户中，可在此页面替换或清除。' : 'The model configuration is account-scoped and can be replaced or cleared here.' }}</span>
          </div>
          <span class="status-badge" :class="modelReadyForDraft ? 'success' : 'warning'">{{ modelReadyForDraft ? (isZh ? '可运行' : 'READY') : (isZh ? '需配置' : 'CHECK CONFIG') }}</span>
        </div>
        <p v-if="modelDraftChanged" class="field-help model-draft-help">{{ isZh ? '模型草稿尚未保存；当前生效状态仍以上方已保存配置为准。' : 'The model draft is not saved; the effective status above still reflects the saved configuration.' }}</p>
        <div class="settings-model-actions">
          <button type="button" class="button secondary" :disabled="testingModel || !loaded" @click="testModel">
            <LoaderCircle v-if="testingModel" class="spin" :size="15" /><RefreshCw v-else :size="15" />
            {{ testingModel ? (isZh ? '测试中...' : 'Testing...') : (isZh ? '测试模型连接' : 'Test model connection') }}
          </button>
          <span v-if="modelTest && modelTestIsCurrent" class="settings-model-result" :class="modelTest.reachable ? 'success' : 'error'" role="status">{{ modelTest.reachable ? (isZh ? '当前草稿连接成功；保存后用于新实验。' : 'Draft connection succeeded; save to use it for new experiments.') : formatCode(modelTest.code, '模型连接测试未通过。', 'The model connection test did not pass.') }}</span>
          <span v-else-if="modelTest" class="settings-model-result error" role="status">{{ isZh ? '模型字段已变化，请重新测试。' : 'Model fields changed; test again.' }}</span>
        </div>
      </section>

      <div class="settings-save-row"><button type="button" class="button secondary" :disabled="saving || !loaded || !settingsDirty" @click="resetDraft"><RotateCcw :size="15" />{{ isZh ? '撤销草稿' : 'Discard draft' }}</button><button type="submit" class="button primary" :disabled="saving || !loaded || !settingsDirty"><Save :size="15" />{{ saving ? (isZh ? '保存中...' : 'Saving...') : (isZh ? '保存设置' : 'Save settings') }}</button></div>
      </form>

      <section class="settings-section" aria-labelledby="security-heading">
        <div class="settings-section-heading"><ShieldCheck :size="17" /><div><h2 id="security-heading">{{ isZh ? '账户安全' : 'Account security' }}</h2><p>{{ isZh ? '修改本地账户密码；更新后其他登录设备会退出。' : 'Change the local account password; other sessions are signed out after an update.' }}</p></div></div>
        <form class="settings-security-form" @submit.prevent="changePassword">
          <label><span>{{ isZh ? '当前密码' : 'Current password' }}</span><input v-model="passwordForm.currentPassword" type="password" autocomplete="current-password" minlength="8" required /></label>
          <label><span>{{ isZh ? '新密码' : 'New password' }}</span><input v-model="passwordForm.newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="256" required /></label>
          <label><span>{{ isZh ? '确认新密码' : 'Confirm new password' }}</span><input v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" minlength="8" maxlength="256" required /></label>
          <div class="settings-action-row"><button type="submit" class="button secondary" :disabled="passwordSaving || !passwordForm.currentPassword || !passwordForm.newPassword || !passwordForm.confirmPassword"><LoaderCircle v-if="passwordSaving" class="spin" :size="15" /><ShieldCheck v-else :size="15" />{{ passwordSaving ? (isZh ? '更新中...' : 'Updating...') : (isZh ? '更新密码' : 'Update password') }}</button></div>
          <p v-if="passwordMessage" class="field-help" role="status">{{ passwordMessage }}</p>
        </form>
      </section>

      <section class="settings-section" aria-labelledby="storage-heading">
        <div class="settings-section-heading"><Database :size="17" /><div><h2 id="storage-heading">{{ isZh ? '数据与存储' : 'Data & storage' }}</h2><p>{{ isZh ? '历史记录保存在本机；导出不包含模型 API key。' : 'History is stored on this machine; exports never include the model API key.' }}</p></div></div>
        <dl v-if="storage" class="settings-details storage-details">
          <div><dt>{{ isZh ? '项目' : 'Projects' }}</dt><dd>{{ storage.projects }}</dd></div>
          <div><dt>{{ isZh ? '会话 / 实验' : 'Sessions / experiments' }}</dt><dd>{{ storage.sessions }} / {{ storage.experiments }}</dd></div>
          <div><dt>{{ isZh ? '验证证据 / 活动' : 'Evidence / events' }}</dt><dd>{{ storage.evidence }} / {{ storage.events }}</dd></div>
          <div><dt>{{ isZh ? '任务记忆修订' : 'Memory revisions' }}</dt><dd>{{ storage.memoryRevisions }}</dd></div>
        </dl>
        <p v-else-if="storageLoading" class="field-help">{{ isZh ? '正在读取数据概览...' : 'Reading data summary...' }}</p>
        <p class="field-help storage-help">{{ isZh ? '“清理运行文件”只移除可重建的临时工作区，不会删除项目、实验、证据或审计历史。' : 'Clean runtime files removes only rebuildable temporary workspaces; projects, experiments, evidence, and audit history stay intact.' }}</p>
        <div class="settings-action-row storage-actions"><button type="button" class="button secondary" :disabled="storageBusy" @click="exportData"><Download :size="15" />{{ isZh ? '导出历史 JSON' : 'Export history JSON' }}</button><button type="button" class="button danger-ghost" :disabled="storageBusy" @click="requestCleanupRuntime"><Trash2 :size="15" />{{ isZh ? '清理运行文件' : 'Clean runtime files' }}</button></div>
      </section>
      <section class="settings-section settings-actions" aria-labelledby="first-run-heading">
        <div class="settings-section-heading"><RotateCcw :size="17" /><div><h2 id="first-run-heading">{{ isZh ? '首次使用' : 'First run' }}</h2><p>{{ isZh ? '重新显示首次使用引导。' : 'Show the first-run guide again.' }}</p></div></div>
        <div class="settings-action-row">
          <button class="button secondary" @click="resetOnboarding"><RotateCcw :size="15" /> {{ isZh ? '重新查看引导' : 'Show first-run guide' }}</button>
          <button class="button danger-ghost" @click="signOut"><LogOut :size="15" /> {{ isZh ? '退出登录' : 'Sign out' }}</button>
        </div>
      </section>
    </main>
    <BaseDialog v-if="showLeaveConfirm" labelled-by="settings-leave-title" described-by="settings-leave-description" @close="showLeaveConfirm = false">
      <div class="dialog-form">
        <header class="dialog-header">
          <div class="dialog-heading"><span class="dialog-icon warning"><AlertTriangle :size="17" /></span><div><p class="eyebrow">{{ isZh ? '未保存草稿' : 'UNSAVED DRAFT' }}</p><h2 id="settings-leave-title">{{ isZh ? '离开设置？' : 'Leave Settings?' }}</h2></div></div>
        </header>
        <p id="settings-leave-description" class="dialog-description">{{ isZh ? '你对设置的修改尚未保存。选择保存后离开、放弃更改，或留在此页继续编辑。' : 'Your settings changes are not saved. Save and leave, discard them, or stay to keep editing.' }}</p>
        <div class="dialog-actions settings-leave-actions">
          <button type="button" class="button secondary" autofocus @click="showLeaveConfirm = false">{{ isZh ? '留在此页' : 'Stay' }}</button>
          <button type="button" class="button danger-ghost" @click="discardAndLeave">{{ isZh ? '放弃更改' : 'Discard changes' }}</button>
          <button type="button" class="button primary" :disabled="saving" @click="saveAndLeave"><Save :size="15" />{{ saving ? (isZh ? '保存中...' : 'Saving...') : (isZh ? '保存并离开' : 'Save and leave') }}</button>
        </div>
      </div>
    </BaseDialog>
    <BaseDialog v-if="showCleanupConfirm" labelled-by="settings-cleanup-title" described-by="settings-cleanup-description" @close="showCleanupConfirm = false">
      <div class="dialog-form">
        <header class="dialog-header">
          <div class="dialog-heading"><span class="dialog-icon warning"><Trash2 :size="17" /></span><div><p class="eyebrow">{{ isZh ? '数据与存储' : 'DATA & STORAGE' }}</p><h2 id="settings-cleanup-title">{{ isZh ? '清理运行文件？' : 'Clean runtime files?' }}</h2></div></div>
        </header>
        <p id="settings-cleanup-description" class="dialog-description">{{ isZh ? '这只会移除可重建的临时隔离工作区和运行文件。项目、会话、实验、证据、活动记录和任务记忆都会保留。' : 'This removes only rebuildable temporary workspaces and runtime files. Projects, sessions, experiments, evidence, activity, and task memory are kept.' }}</p>
        <div class="dialog-actions settings-leave-actions">
          <button type="button" class="button secondary" autofocus @click="showCleanupConfirm = false">{{ isZh ? '取消' : 'Cancel' }}</button>
          <button type="button" class="button danger-ghost" @click="cleanupRuntime"><Trash2 :size="15" />{{ isZh ? '确认清理' : 'Clean files' }}</button>
        </div>
      </div>
    </BaseDialog>
  </div>
</template>
