<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  AlertTriangle,
  ArrowUp,
  Check,
  CheckCircle2,
  Eye,
  FlaskConical,
  LoaderCircle,
  Menu,
  Pause,
  Play,
  RefreshCw,
  Settings2,
  ShieldCheck,
  Wrench,
} from 'lucide-vue-next'
import { api, type Experiment, type ModelConfigurationStatus, type Project, type PromotionPreview, type RunEvent, type Session } from '../api'
import { useLocale } from '../i18n'
import { formatDate, formatError, statusLabel, statusTone } from '../ui'
import MarkdownContent from './MarkdownContent.vue'

const props = defineProps<{
  project: Project | null
  session: Session | null
  experiments: Experiment[]
  selectedExperimentId: string | null
  activity: RunEvent[]
  streamState: 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline'
  actionBusy: boolean
  detailLoading: boolean
  promotionPreview: PromotionPreview | null
  navigationOpen?: boolean
}>()

const emit = defineEmits<{
  submit: [task: string]
  newTask: []
  select: [experimentId: string]
  review: [experimentId: string]
  openSettings: []
  retry: [experimentId: string]
  reconnect: []
  start: [experimentId: string]
  cancel: [experimentId: string]
  openNavigation: []
}>()

const { text } = useLocale()
const draft = ref('')
const composer = ref<HTMLTextAreaElement | null>(null)
const submittedForProject = ref<string | null>(null)
const modelStatus = ref<ModelConfigurationStatus | null>(null)
const modelStatusLoading = ref(false)
const modelStatusError = ref<string | null>(null)
let modelStatusRequest = 0

function draftStorageKey(projectId: string | null | undefined) {
  return projectId ? `offcanon.task-draft.${projectId}` : null
}

function readDraft(projectId: string | null | undefined) {
  const key = draftStorageKey(projectId)
  if (!key) return ''
  try { return window.sessionStorage.getItem(key) ?? '' } catch { return '' }
}

function saveDraft(projectId: string | null | undefined, value: string) {
  const key = draftStorageKey(projectId)
  if (!key) return
  try {
    if (value.trim()) window.sessionStorage.setItem(key, value.slice(0, 20_000))
    else window.sessionStorage.removeItem(key)
  } catch {
    // The in-memory draft remains authoritative when storage is unavailable.
  }
}

function clearSavedDraft(projectId: string | null | undefined) {
  const key = draftStorageKey(projectId)
  if (!key) return
  try { window.sessionStorage.removeItem(key) } catch { /* best effort */ }
}

const latest = computed(() => props.experiments.at(-1) ?? null)
const continuableStatuses = new Set(['VERIFIED', 'REJECTED', 'STALE', 'PROMOTED', 'FAILED', 'CANCELLED'])
const workingStatuses = new Set([
  'CREATED',
  'SNAPSHOTTING',
  'RUNNING',
  'AGENT_COMPLETED',
  'VERIFYING',
  'PREPARING_PROMOTION',
  'PROMOTING',
])
// The backend accepts cancellation before the worker is scheduled as well as
// while it is running. Keep the conversation controls in sync with that API.
const cancellableStatuses = new Set(['READY_TO_RUN', 'RUNNING', 'AGENT_COMPLETED', 'VERIFYING'])
const composerAvailable = computed(() => !latest.value || continuableStatuses.has(latest.value.status))
const modelReady = computed(() => Boolean(modelStatus.value?.apiKeyConfigured
  && modelStatus.value.endpointConfigured
  && modelStatus.value.modelConfigured
  && modelStatus.value.endpointValid))
const canSubmit = computed(() => !!props.project && modelReady.value && composerAvailable.value && draft.value.trim().length > 0 && !props.actionBusy)

const modelGateCopy = computed(() => {
  const status = modelStatus.value
  if (modelStatusLoading.value) return {
    title: text('正在检查模型配置', 'Checking model configuration'),
    detail: text('确认 Agent 是否可以开始新实验。', 'Confirming that the agent can start a new experiment.'),
  }
  if (modelStatusError.value) return {
    title: text('无法确认模型配置', 'Unable to check model configuration'),
    detail: text('无法确认服务端运行条件。请重新检查；如果持续失败，请联系运行 Offcanon 的管理员。', 'The server run requirements could not be confirmed. Check again; if it persists, contact the Offcanon administrator.'),
  }
  if (!status?.apiKeyConfigured) return {
    title: text('开始前需要配置模型 API Key', 'A model API key is required before starting'),
    detail: text('请在设置中保存当前账户的 API Key。', 'Save an API key for this account in Settings.'),
  }
  if (!status.endpointConfigured || !status.modelConfigured) return {
    title: text('请选择模型服务和模型', 'Choose a model service and model'),
    detail: text('在设置中保存 Endpoint 和模型名后即可发送任务。', 'Save an endpoint and model name in Settings before sending a task.'),
  }
  if (!status.endpointValid) return {
    title: text('模型地址无效', 'The model endpoint is invalid'),
    detail: text('请输入不带凭据、查询参数或片段的 HTTP(S) 地址。', 'Enter an HTTP(S) URL without credentials, query parameters, or fragments.'),
  }
  return null
})
const placeholder = computed(() => {
  if (!props.project) return text('先打开一个本机项目', 'Open a local project to start')
  if (!latest.value) return text('告诉 Agent 你想完成什么', 'Tell the agent what you want to build')
  if (latest.value.status === 'READY_TO_RUN') return text('先启动当前任务', 'Start the current task first')
  if (latest.value.status === 'RECOVERY_REQUIRED') return text('先恢复项目状态', 'Reconcile the project state first')
  if (latest.value.status === 'AGENT_COMPLETED') return text('正在锁定结果并准备可信验证', 'Sealing the result and preparing trusted verification')
  if (['PREPARING_PROMOTION', 'PROMOTING'].includes(latest.value.status)) {
    return text('正在应用到项目，请稍候', 'Applying the result to the project; please wait')
  }
  if (!composerAvailable.value) return text('Agent 正在处理当前任务', 'The agent is working on this task')
  if (latest.value.status === 'STALE') return text('基于最新主线继续这项任务', 'Continue this task on the latest project state')
  if (latest.value.status === 'FAILED' || latest.value.status === 'REJECTED' || latest.value.status === 'CANCELLED') return text('继续这项任务，或补充新的要求', 'Continue this task, or add a new requirement')
  return text('继续告诉 Agent 下一步', 'Tell the agent what to do next')
})

function resizeComposer() {
  if (!composer.value) return
  composer.value.style.height = 'auto'
  composer.value.style.height = `${Math.min(150, Math.max(48, composer.value.scrollHeight))}px`
}

function submit() {
  if (!canSubmit.value) return
  submittedForProject.value = props.project?.id ?? null
  emit('submit', draft.value.trim())
}

function onKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    submit()
  }
}

function eventLabel(event: RunEvent) {
  const labels: Record<string, [string, string]> = {
    MODEL_REQUEST: ['正在思考下一步', 'Planning the next step'],
    MODEL_RESPONSE: ['已决定下一步', 'Next step chosen'],
    TOOL_CALL: ['正在调用工具', 'Calling a tool'],
    TOOL_RESULT: ['已完成一次操作', 'Completed an operation'],
    CONTEXT_SNAPSHOT: ['正在整理上下文', 'Preparing context'],
    CONTEXT_COMPACTED: ['正在压缩上下文', 'Compacting context'],
    MODEL_RETRY: ['模型暂时不可用，稍后重试', 'Model unavailable; retrying shortly'],
    SESSION_CONTEXT_IMPORTED: ['已承接上一轮任务上下文', 'Previous task context carried forward'],
    TASK_MEMORY_AGENT_PROPOSAL_RECORDED: ['已保存一条待确认记忆', 'Saved a memory proposal'],
    TASK_MEMORY_VERIFIED_FACT_RECORDED: ['已记录可信验证事实', 'Recorded a verified fact'],
    TASK_MEMORY_UNAVAILABLE: ['记忆暂时不可用，继续使用当前代码', 'Memory unavailable; continuing with current code'],
    TASK_MEMORY_RECORD_FAILED: ['记忆未保存，运行结果不受影响', 'Memory was not saved; run result is unaffected'],
    VERIFICATION_STARTED: ['正在运行验证', 'Running verification'],
    VERIFICATION_FINISHED: ['验证已完成', 'Verification finished'],
    RESULT_SNAPSHOT_SEALED: ['已锁定实验结果', 'Result sealed in the experiment'],
    EXPERIMENT_STARTED: ['已启动隔离实验', 'Isolated experiment started'],
    AGENT_COMPLETED: ['代理已完成，正在保存结果', 'Agent finished; saving the result'],
    EXPERIMENT_RECOVERED: ['已恢复中断的实验', 'Interrupted experiment recovered'],
    PROMOTION_PREPARING: ['正在准备应用', 'Preparing to apply the result'],
    PROMOTION_VERIFICATION_STARTED: ['正在检查应用候选', 'Checking the application candidate'],
    PROMOTION_BLOCKED: ['应用被阻止', 'Application blocked'],
    PROMOTION_RECOVERY_REQUIRED: ['需要恢复应用状态', 'Application recovery required'],
    PROMOTION_RECOVERY_DEFERRED: ['应用恢复已延期', 'Application recovery deferred'],
    PROMOTION_RECOVERED: ['应用状态已恢复', 'Application state recovered'],
    PROMOTION_MANUALLY_RECONCILED: ['应用状态已手动确认', 'Application state reconciled'],
    PROMOTING: ['正在应用到主线', 'Applying to canonical'],
    PROMOTED: ['已应用到主线', 'Applied to canonical'],
    EXPERIMENT_FAILED: ['实验运行失败', 'Experiment run failed'],
    EXPERIMENT_CANCELLED: ['实验已取消', 'Experiment cancelled'],
  }
  const pair = labels[event.type] ?? [statusLabel(event.type), statusLabel(event.type)]
  return text(pair[0], pair[1])
}

function recentEvents(experimentId: string) {
  return props.activity.filter((event) => event.experimentId === experimentId).slice(-3)
}

function workingCopy(experiment: Experiment) {
  const latestEvent = recentEvents(experiment.id).at(-1)
  if (latestEvent) return eventLabel(latestEvent)
  if (experiment.status === 'CREATED' || experiment.status === 'SNAPSHOTTING') {
    return text('正在准备隔离工作区', 'Preparing the isolated workspace')
  }
  return statusDetail(experiment)
}

function statusHeadline(experiment: Experiment) {
  if (experiment.status === 'PROMOTED') return text('已应用到项目', 'Applied to project')
  if (experiment.status === 'VERIFIED') return text('结果已经准备好审阅', 'The result is ready to review')
  if (experiment.status === 'STALE') return text('项目已更新，结果需要在最新主线上继续', 'The project changed; continue on the latest state')
  if (experiment.status === 'FAILED') return text('这次运行没有完成', 'This run did not finish')
  if (experiment.status === 'REJECTED') return text('验证没有通过，可以继续修复', 'Verification failed; continue fixing')
  if (experiment.status === 'CANCELLED') return text('运行已停止', 'Run stopped')
  if (experiment.status === 'CREATED' || experiment.status === 'SNAPSHOTTING') return text('正在准备隔离工作区', 'Preparing the isolated workspace')
  if (experiment.status === 'RUNNING') return text('Agent 正在处理这项任务', 'The agent is working on this task')
  if (experiment.status === 'AGENT_COMPLETED') return text('代理已完成，正在保存结果', 'Agent finished; saving the result')
  if (experiment.status === 'VERIFYING') return text('正在检查实验结果', 'Checking the experiment result')
  if (experiment.status === 'PREPARING_PROMOTION') return text('正在准备应用', 'Preparing to apply the result')
  if (experiment.status === 'PROMOTING') return text('正在应用到项目', 'Applying the result to the project')
  if (experiment.status === 'RECOVERY_REQUIRED') return text('需要恢复项目状态', 'Project state needs recovery')
  if (experiment.status === 'READY_TO_RUN') return text('准备开始', 'Ready to start')
  return statusLabel(experiment.status)
}

function statusDetail(experiment: Experiment) {
  if (experiment.status === 'PROMOTED') return text('主线已经更新。你可以继续提出下一步。', 'Canonical is updated. You can ask for the next step.')
  if (experiment.status === 'VERIFIED') return text('变更仍在隔离实验中，确认后才会进入项目。', 'Changes are still isolated in the experiment until you confirm.')
  if (experiment.status === 'STALE') return text('主线没有被覆盖；继续会重新面对当前代码。', 'Canonical was not overwritten; continuing will re-check the current code.')
  if (experiment.status === 'FAILED') return text('主线未变化时，已有意图和有效草稿会在继续时保留。', 'If canonical is unchanged, the intent and any valid draft will be carried into the next run.')
  if (experiment.status === 'REJECTED') return text('继续时 Agent 会根据最新验证结果修复。', 'The next run will use the verification result to guide a fix.')
  if (experiment.status === 'CANCELLED') return text('可以在主线未变化时承接当前实验继续。', 'You can continue from this experiment when canonical is unchanged.')
  if (experiment.status === 'CREATED' || experiment.status === 'SNAPSHOTTING') return text('正在创建隔离工作区。', 'The isolated workspace is being created.')
  if (experiment.status === 'AGENT_COMPLETED') return text('正在锁定结果并开始可信验证。', 'The result is being sealed before trusted verification starts.')
  if (experiment.status === 'VERIFYING') return text('验证完成后，结果才可以进入审阅。', 'The result will be ready for review after verification completes.')
  if (experiment.status === 'PREPARING_PROMOTION') return text('正在准备受保护的主线更新。', 'Preparing a guarded update to canonical.')
  if (experiment.status === 'PROMOTING') return text('正在完成主线更新，请稍候。', 'Finishing the canonical update; please wait.')
  if (experiment.status === 'RECOVERY_REQUIRED') return text('上一次应用没有完成，请先恢复项目状态。', 'The previous application did not finish; reconcile the project state first.')
  if (experiment.status === 'READY_TO_RUN') return text('Agent 会在独立实验空间中工作。', 'The agent will work in an isolated experiment.')
  return text('主线在运行期间保持不变。', 'Canonical stays unchanged while the run advances.')
}

function failureText(experiment: Experiment) {
  const reason = experiment.failureReason ?? ''
  if (reason.includes('MODEL_NOT_CONFIGURED')) return text('尚未配置模型服务，请先完成设置', 'Model service is not configured; open Settings first')
  if (reason.includes('MODEL_ENDPOINT_INVALID')) return text('当前模型服务地址不可用，请检查设置', 'The model service endpoint is not usable; check Settings')
  if (reason.includes('MODEL_REQUEST_FAILED')) return text('模型服务拒绝了请求，请测试模型连接', 'The model service rejected the request; test the connection')
  if (reason.includes('MODEL_TRANSIENT_FAILURE')) return text('模型服务暂时不可用', 'The model service was temporarily unavailable')
  if (reason.includes('AGENT_TIMEOUT')) return text('运行超时，可以再次运行或缩小任务范围', 'The run timed out; retry or narrow the task')
  if (reason.includes('MAX_STEPS_EXCEEDED')) return text('运行达到步数上限，可以再次运行或缩小任务范围', 'The run reached its step limit; retry or narrow the task')
  if (reason.includes('TOOL_CALL_LIMIT_EXCEEDED')) return text('工具调用达到上限，可以再次运行或缩小任务范围', 'The tool-call limit was reached; retry or narrow the task')
  if (reason.includes('VERIFICATION')) return text('验证发现需要处理的问题', 'Verification found an issue to address')
  if (reason.includes('STALE')) return text('这个实验基于旧的项目状态', 'This experiment is based on an older project state')
  return statusHeadline(experiment)
}

function failureCode(experiment: Experiment) {
  return (experiment.failureReason ?? '').match(/^\s*([A-Z][A-Z0-9_]{2,})(?=\s*:|\s|$)/)?.[1] ?? ''
}

function canRetry(experiment: Experiment) {
  return ['MODEL_TRANSIENT_FAILURE', 'AGENT_TIMEOUT', 'MAX_STEPS_EXCEEDED', 'TOOL_CALL_LIMIT_EXCEEDED'].includes(failureCode(experiment))
}

function needsModelSettings(experiment: Experiment) {
  return ['MODEL_NOT_CONFIGURED', 'MODEL_ENDPOINT_INVALID', 'MODEL_REQUEST_FAILED']
    .includes(failureCode(experiment))
}

function streamCopy() {
  if (props.streamState === 'live') return text('实时更新', 'Live updates')
  if (props.streamState === 'reconnecting') return text('正在重连', 'Reconnecting')
  if (props.streamState === 'offline') return text('更新暂时不可用', 'Updates unavailable')
  return text('连接中', 'Connecting')
}

async function refreshModelStatus() {
  const requestId = ++modelStatusRequest
  modelStatusLoading.value = true
  modelStatusError.value = null
  try {
    const status = await api.modelStatus()
    if (requestId === modelStatusRequest) modelStatus.value = status
  } catch (cause) {
    if (requestId === modelStatusRequest) {
      modelStatusError.value = formatError(cause, '无法读取模型配置。', 'Unable to read model configuration.')
    }
  } finally {
    if (requestId === modelStatusRequest) modelStatusLoading.value = false
  }
}

async function focusComposer() {
  await nextTick()
  composer.value?.focus()
}

watch(() => latest.value?.id, (next, previous) => {
  if (!next || next === previous) return
  if (submittedForProject.value === props.project?.id) {
    clearSavedDraft(props.project?.id)
    draft.value = ''
    submittedForProject.value = null
    if (composer.value) composer.value.style.height = '48px'
  }
})

watch(() => props.project?.id, (next, previous) => {
  if (next !== previous) submittedForProject.value = null
  if (next !== previous) draft.value = readDraft(next)
  if (next && next !== previous) void refreshModelStatus()
}, { immediate: true })

watch(draft, (value) => saveDraft(props.project?.id, value))

defineExpose({ focusComposer })
</script>

<template>
  <main class="agent-thread" :aria-label="text('Coding Agent 工作区', 'Coding Agent workspace')">
    <header class="thread-header">
      <button class="icon-button thread-nav-button" :aria-label="text('打开任务导航', 'Open task navigation')" :title="text('任务', 'Tasks')" :aria-expanded="props.navigationOpen ? 'true' : 'false'" aria-controls="offcanon-task-navigation" @click="emit('openNavigation')"><Menu :size="18" /></button>
      <div class="thread-heading">
        <span class="thread-kicker"><span class="live-pip" />{{ project ? text('Coding Agent', 'Coding Agent') : text('开始协作', 'Start collaborating') }}</span>
        <h1>{{ session?.title || (project ? text('新任务', 'New task') : text('打开一个项目', 'Open a project')) }}</h1>
        <p v-if="project" class="thread-path" :title="project.canonicalPath">{{ project.canonicalPath }}</p>
      </div>
      <div v-if="session" class="thread-actions">
        <span class="thread-guard"><ShieldCheck :size="14" />{{ text('实验隔离', 'Experiment isolated') }}</span>
        <button class="button secondary compact" @click="emit('newTask')"><Wrench :size="14" />{{ text('新任务', 'New task') }}</button>
      </div>
    </header>

    <div v-if="project && streamState === 'offline'" class="thread-stream-notice" role="status">
      <AlertTriangle :size="14" />
      <span>{{ text('实时活动连接已断开；任务状态仍可通过刷新获取。', 'Live activity is offline; refresh to retrieve the latest task state.') }}</span>
      <button class="button secondary compact" @click="emit('reconnect')"><RefreshCw :size="14" />{{ text('重新连接', 'Reconnect') }}</button>
    </div>
    <div v-else-if="project && streamState === 'reconnecting'" class="thread-stream-notice reconnecting" role="status">
      <LoaderCircle class="spin" :size="14" /><span>{{ text('实时活动连接正在恢复。', 'Live activity connection is recovering.') }}</span>
    </div>

    <section v-if="!project" class="thread-welcome">
      <div class="welcome-mark"><FlaskConical :size="24" /></div>
      <h2>{{ text('把下一件编程工作交给 Agent', 'Give the next coding task to the agent') }}</h2>
    </section>

    <section v-else-if="experiments.length" class="turn-list" aria-live="polite">
      <article
        v-for="experiment in experiments"
        :key="experiment.id"
        class="conversation-turn"
        :class="{ selected: experiment.id === selectedExperimentId, [`tone-${statusTone(experiment.status)}`]: true }"
        @click="emit('select', experiment.id)"
      >
        <div class="turn-user">
          <span class="turn-label">{{ text('你', 'You') }}</span>
          <MarkdownContent class="task-markdown" :source="experiment.task" />
          <time :datetime="experiment.createdAt">{{ formatDate(experiment.createdAt) }}</time>
        </div>

        <div class="turn-agent">
          <div class="agent-avatar">O</div>
          <div class="agent-body">
            <div class="agent-meta"><strong>Offcanon</strong><span class="turn-state" :class="statusTone(experiment.status)">{{ statusHeadline(experiment) }}</span></div>
            <MarkdownContent v-if="experiment.agentSummary && !workingStatuses.has(experiment.status)" class="agent-summary" :source="experiment.agentSummary" />
            <p v-else-if="['FAILED', 'REJECTED', 'STALE', 'CANCELLED'].includes(experiment.status)" class="agent-summary failure-copy">{{ failureText(experiment) }}</p>
            <div v-else-if="workingStatuses.has(experiment.status)" class="working-row">
              <LoaderCircle class="spin" :size="16" /><span>{{ workingCopy(experiment) }}</span><small>{{ streamCopy() }}</small>
            </div>
            <p v-else class="agent-summary muted-copy">{{ statusDetail(experiment) }}</p>

            <div v-if="experiment.status === 'VERIFIED'" class="turn-decision">
              <div><CheckCircle2 :size="16" /><span>{{ text('已通过可信检查', 'Trusted checks passed') }}</span></div>
              <button class="button success compact" @click.stop="emit('review', experiment.id)"><Eye :size="14" />{{ text('查看变更', 'Review changes') }}</button>
            </div>
            <div v-else-if="experiment.status === 'PROMOTED'" class="turn-applied"><Check :size="15" /><span>{{ text('已应用到真实项目', 'Applied to the real project') }}</span><button class="text-button" @click.stop="emit('review', experiment.id)">{{ text('查看记录', 'View record') }}</button></div>
            <div v-else-if="experiment.status === 'READY_TO_RUN'" class="turn-actions">
              <button class="button primary compact" :disabled="actionBusy || !modelReady" @click.stop="emit('start', experiment.id)"><Play :size="14" />{{ text('开始', 'Start') }}</button>
              <button class="button danger-ghost compact" :disabled="actionBusy" @click.stop="emit('cancel', experiment.id)"><Pause :size="14" />{{ text('取消', 'Cancel') }}</button>
            </div>
            <div v-else-if="['FAILED', 'REJECTED', 'STALE', 'CANCELLED', 'RECOVERY_REQUIRED'].includes(experiment.status)" class="turn-recovery">
              <AlertTriangle :size="15" />
              <span>{{ failureText(experiment) }}</span>
              <button v-if="needsModelSettings(experiment)" class="button secondary compact" :disabled="actionBusy" @click.stop="emit('openSettings')">{{ text('去设置', 'Open Settings') }}</button>
              <button v-else-if="canRetry(experiment)" class="button secondary compact" :disabled="actionBusy" @click.stop="emit('retry', experiment.id)">{{ text('再次运行', 'Retry') }}</button>
              <button class="button secondary compact" :disabled="actionBusy" @click.stop="emit('review', experiment.id)">{{ text('查看原因', 'See why') }}</button>
            </div>
            <div v-else-if="workingStatuses.has(experiment.status)" class="turn-actions">
              <button v-if="cancellableStatuses.has(experiment.status)" class="button danger-ghost compact" :disabled="actionBusy" @click.stop="emit('cancel', experiment.id)"><Pause :size="14" />{{ text('停止', 'Stop') }}</button>
              <span v-else class="muted-copy">{{ statusDetail(experiment) }}</span>
            </div>
            <div v-if="experiment.status === 'VERIFIED' && promotionPreview && experiment.id === selectedExperimentId && !promotionPreview.promotable" class="turn-note"><ShieldCheck :size="14" />{{ text('结果可查看；应用条件会在审阅面板中说明。', 'The result is available; application conditions are shown in review.') }}</div>
          </div>
        </div>
      </article>
    </section>

    <section v-else class="thread-empty">
      <div class="empty-line"><FlaskConical :size="18" /><span>{{ text('还没有任务', 'No tasks yet') }}</span></div>
    </section>

    <div v-if="project && modelGateCopy" class="agent-model-gate" role="status">
      <LoaderCircle v-if="modelStatusLoading" class="spin" :size="16" />
      <AlertTriangle v-else :size="16" />
      <div><strong>{{ modelGateCopy.title }}</strong><span>{{ modelGateCopy.detail }}</span></div>
      <button class="button secondary compact" @click="emit('openSettings')"><Settings2 :size="14" />{{ text('打开设置', 'Open Settings') }}</button>
      <button class="icon-button small" :aria-label="text('重新检查模型配置', 'Refresh model configuration')" :title="text('重新检查', 'Refresh check')" @click="refreshModelStatus"><RefreshCw :size="14" /></button>
    </div>

    <footer class="composer-wrap">
      <form class="agent-composer" @submit.prevent="submit">
        <textarea ref="composer" v-model="draft" :placeholder="placeholder" :disabled="!project || !composerAvailable || actionBusy" rows="1" :aria-label="text('任务输入', 'Task input')" @input="resizeComposer" @keydown="onKeydown" />
        <div class="composer-bottom">
          <span class="composer-hint"><ShieldCheck :size="13" />{{ session?.title ?? text('新任务', 'New task') }}</span>
          <button type="submit" class="send-button" :disabled="!canSubmit" :aria-label="text('发送任务', 'Send task')" :title="text('发送任务', 'Send task')"><ArrowUp :size="17" /></button>
        </div>
      </form>
    </footer>
  </main>
</template>
