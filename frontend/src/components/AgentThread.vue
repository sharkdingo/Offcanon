<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  AlertTriangle,
  ArrowUp,
  Check,
  CheckCircle2,
  CircleDot,
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
import { experimentBlocksSession, experimentDisplayTone, formatDate, formatError, sealedResultWaiting, statusLabel, verificationPolicyChanged } from '../ui'
import { runEventLabel } from '../runEvents'
import MarkdownContent from './MarkdownContent.vue'
import RunActivityTimeline from './RunActivityTimeline.vue'

const props = defineProps<{
  project: Project | null
  session: Session | null
  experiments: Experiment[]
  selectedExperimentId: string | null
  activity: RunEvent[]
  activityExperimentId: string | null
  activityTruncated?: boolean
  eventWarning?: string | null
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
  verify: [experimentId: string]
  editProject: []
  openNavigation: []
  modelReadiness: [ready: boolean]
}>()

const { text } = useLocale()
const draft = ref('')
const composer = ref<HTMLTextAreaElement | null>(null)
const turnList = ref<HTMLElement | null>(null)
const followLiveActivity = ref(true)
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
const sealedResultCanContinue = computed(() => !!latest.value
  && awaitsVerification(latest.value)
  && (props.project?.verificationCommands.length === 0 || verificationPolicyChanged(latest.value)))
const composerAvailable = computed(() => !latest.value
  || continuableStatuses.has(latest.value.status)
  || sealedResultCanContinue.value)
const modelReady = computed(() => !modelStatusLoading.value
  && !modelStatusError.value
  && Boolean(modelStatus.value?.apiKeyConfigured
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
  const missing: string[] = []
  if (!status?.apiKeyConfigured) missing.push(text('API Key', 'API key'))
  if (!status?.endpointConfigured) missing.push(text('Endpoint', 'endpoint'))
  if (!status?.modelConfigured) missing.push(text('模型名', 'model name'))
  if (missing.length) return {
    title: text(`开始前还缺少：${missing.join('、')}`, `Missing before starting: ${missing.join(', ')}`),
    detail: text('请在设置中补齐当前账户的模型服务配置。', 'Complete the model service settings for this account.'),
  }
  if (!status?.endpointValid) return {
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
  if (awaitsVerification(latest.value)) {
    if (verificationPolicyChanged(latest.value)) {
      return text('验收命令已变化；可重新验收，也可以继续提出下一步', 'Acceptance commands changed; verify again or ask for the next step')
    }
    return props.project?.verificationCommands.length
      ? text('结果已封存，请先运行验收', 'The result is sealed; run verification first')
      : text('继续提出下一步；验收命令可以稍后设置', 'Ask for the next step; acceptance commands can be set later')
  }
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

function selectTurn(event: MouseEvent, experimentId: string) {
  const target = event.target
  if (target instanceof Element && target.closest('a,button,input,textarea,select,summary')) return
  emit('select', experimentId)
}

function onKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    submit()
  }
}

function recentEvents(experimentId: string) {
  if (props.activityExperimentId !== experimentId) return []
  return props.activity.slice(-3)
}

function workingCopy(experiment: Experiment) {
  const latestEvent = recentEvents(experiment.id).at(-1)
  if (latestEvent) return runEventLabel(latestEvent, text)
  if (experiment.status === 'CREATED' || experiment.status === 'SNAPSHOTTING') {
    return text('正在准备隔离工作区', 'Preparing the isolated workspace')
  }
  return statusDetail(experiment)
}

function statusHeadline(experiment: Experiment) {
  if (experiment.status === 'PROMOTED') return text('已应用到项目', 'Applied to project')
  if (experiment.status === 'VERIFIED') return text('结果已经准备好审阅', 'The result is ready to review')
  if (experiment.status === 'STALE') return experiment.failureReason?.includes('VERIFICATION_POLICY_CHANGED')
    ? text('验收命令已修改，结果已过期', 'Acceptance commands changed; result is stale')
    : text('项目已更新，结果需要在最新主线上继续', 'The project changed; continue on the latest state')
  if (experiment.status === 'FAILED') return text('这次运行没有完成', 'This run did not finish')
  if (experiment.status === 'REJECTED') return text('验收没有通过，可以继续修复', 'Checks failed; continue fixing')
  if (experiment.status === 'CANCELLED') return text('运行已停止', 'Run stopped')
  if (awaitsVerification(experiment)) {
    if (verificationPolicyChanged(experiment)) {
      return text('验收策略已变化，需重新验收', 'Acceptance policy changed; ready to reverify')
    }
    if (experiment.failureReason?.trim()) {
      return text('验收已中断，可重新运行', 'Verification interrupted; ready to retry')
    }
    return props.project?.verificationCommands.length
      ? text('等待运行验收', 'Ready for verification')
      : text('已封存，可继续', 'Sealed; ready to continue')
  }
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
  if (experiment.status === 'STALE') return experiment.failureReason?.includes('VERIFICATION_POLICY_CHANGED')
    ? text('验收命令已经变化；这个结果不会按旧命令应用。继续任务后会按新命令重新验收。', 'The acceptance commands changed; this result will not be applied under the old policy. Continue the task to verify under the new commands.')
    : text('主线没有被覆盖；继续会重新面对当前代码。', 'Canonical was not overwritten; continuing will re-check the current code.')
  if (experiment.status === 'FAILED') return text('主线未变化时，已有意图和有效草稿会在继续时保留。', 'If canonical is unchanged, the intent and any valid draft will be carried into the next run.')
  if (experiment.status === 'REJECTED') return text('继续时 Agent 会根据最新验收结果修复。', 'The next run will use the acceptance result to guide a fix.')
  if (experiment.status === 'CANCELLED') return text('可以在主线未变化时承接当前实验继续。', 'You can continue from this experiment when canonical is unchanged.')
  if (awaitsVerification(experiment)) {
    if (verificationPolicyChanged(experiment)) {
      return text('项目验收命令已更新；这个封存结果仍然保留，请按新命令重新验收。', 'The project acceptance commands changed; this sealed result is retained and ready to verify under the new policy.')
    }
    if (experiment.failureReason?.trim()) {
      return text('上次验收没有完成；可以重新运行验收或继续任务。', 'The previous verification did not finish; retry verification or continue the task.')
    }
    return props.project?.verificationCommands.length
      ? text('结果已锁定，运行验收后才能进入可应用状态。', 'The result is sealed; run acceptance checks before it can be applied.')
      : text('结果已锁定并保留；你可以继续迭代，也可以稍后在项目设置中补充命令再验收。', 'The result is sealed and retained; continue iterating, or add commands in project settings and verify it later.')
  }
  if (experiment.status === 'CREATED' || experiment.status === 'SNAPSHOTTING') return text('正在创建隔离工作区。', 'The isolated workspace is being created.')
  if (experiment.status === 'AGENT_COMPLETED') return text('正在锁定结果并开始可信验证。', 'The result is being sealed before trusted verification starts.')
  if (experiment.status === 'VERIFYING') return text('验收通过后，结果才可以应用。', 'The result can be applied after verification passes.')
  if (experiment.status === 'PREPARING_PROMOTION') return text('正在准备受保护的主线更新。', 'Preparing a guarded update to canonical.')
  if (experiment.status === 'PROMOTING') return text('正在完成主线更新，请稍候。', 'Finishing the canonical update; please wait.')
  if (experiment.status === 'RECOVERY_REQUIRED') return text('上一次应用没有完成，请先恢复项目状态。', 'The previous application did not finish; reconcile the project state first.')
  if (experiment.status === 'READY_TO_RUN') return text('Agent 会在独立实验空间中工作。', 'The agent will work in an isolated experiment.')
  return text('主线在运行期间保持不变。', 'Canonical stays unchanged while the run advances.')
}

function awaitsVerification(experiment: Experiment) {
  return sealedResultWaiting(experiment)
}

function displayTone(experiment: Experiment) {
  return experimentDisplayTone(experiment)
}

function failureText(experiment: Experiment) {
  const reason = experiment.failureReason ?? ''
  if (reason.includes('MODEL_NOT_CONFIGURED')) return text('尚未配置模型服务，请先完成设置', 'Model service is not configured; open Settings first')
  if (reason.includes('MODEL_ENDPOINT_INVALID')) return text('当前模型服务地址不可用，请检查设置', 'The model service endpoint is not usable; check Settings')
  if (reason.includes('MODEL_API_KEY_INVALID')) return text('模型 API key 格式不可用，请在设置中重新输入', 'The model API key is not usable; enter it again in Settings')
  if (reason.includes('MODEL_REQUEST_FAILED')) return text('模型服务拒绝了请求，请测试模型连接', 'The model service rejected the request; test the connection')
  if (reason.includes('MODEL_REQUEST_INVALID')) return text('模型请求配置无效，请检查设置', 'The model request is invalid; check Settings')
  if (reason.includes('MODEL_TRANSIENT_FAILURE')) return text('模型服务暂时不可用', 'The model service was temporarily unavailable')
  if (reason.includes('AGENT_TIMEOUT')) return text('运行超时，可以再次运行或缩小任务范围', 'The run timed out; retry or narrow the task')
  if (reason.includes('MAX_STEPS_EXCEEDED')) return text('运行达到步数上限，可以再次运行或缩小任务范围', 'The run reached its step limit; retry or narrow the task')
  if (reason.includes('TOOL_CALL_LIMIT_EXCEEDED')) return text('工具调用达到上限，可以再次运行或缩小任务范围', 'The tool-call limit was reached; retry or narrow the task')
  if (reason.includes('VERIFICATION_POLICY_CHANGED')) return text('项目验收命令已修改，这个结果已标记为过期；请继续任务并按新命令重新验收', 'The project acceptance commands changed, so this result is stale; continue the task and verify under the new commands')
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

function canReverify(experiment: Experiment) {
  return (experiment.status === 'REJECTED' || awaitsVerification(experiment))
    && !!experiment.resultSnapshotId
}

function blockedByOtherLifecycle(experiment: Experiment) {
  return props.experiments.some((candidate) => candidate.id !== experiment.id
    && candidate.sessionId === experiment.sessionId
    && experimentBlocksSession(candidate))
}

function activityRunning(experiment: Experiment) {
  return workingStatuses.has(experiment.status) && !awaitsVerification(experiment)
}

function needsModelSettings(experiment: Experiment) {
  return ['MODEL_NOT_CONFIGURED', 'MODEL_ENDPOINT_INVALID', 'MODEL_API_KEY_INVALID', 'MODEL_REQUEST_INVALID', 'MODEL_REQUEST_FAILED']
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

function updateFollowState() {
  const list = turnList.value
  if (!list) return
  followLiveActivity.value = list.scrollHeight - list.scrollTop - list.clientHeight < 140
}

async function scrollToLatest(force = false) {
  if (!force && !followLiveActivity.value) return
  await nextTick()
  const list = turnList.value
  if (list) list.scrollTop = list.scrollHeight
}

watch(() => latest.value?.id, (next, previous) => {
  if (!next || next === previous) return
  if (submittedForProject.value === props.project?.id) {
    clearSavedDraft(props.project?.id)
    draft.value = ''
    submittedForProject.value = null
    if (composer.value) composer.value.style.height = '48px'
  }
  followLiveActivity.value = true
  void scrollToLatest(true)
})

watch(() => props.project?.id, (next, previous) => {
  if (next !== previous) submittedForProject.value = null
  if (next !== previous) draft.value = readDraft(next)
  if (next && next !== previous) void refreshModelStatus()
}, { immediate: true })

watch(draft, (value) => saveDraft(props.project?.id, value))
watch(modelReady, (ready) => emit('modelReadiness', ready), { immediate: true })
watch(() => props.activity.at(-1)?.sequence, () => void scrollToLatest())

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
      <div v-if="project" class="thread-actions">
        <span v-if="session" class="thread-guard"><ShieldCheck :size="14" />{{ text('实验隔离', 'Experiment isolated') }}</span>
        <button class="button secondary compact project-settings-button" :disabled="actionBusy" :aria-label="text('项目设置', 'Project settings')" :title="text('项目设置', 'Project settings')" @click="emit('editProject')"><Settings2 :size="14" />{{ text('项目设置', 'Project settings') }}</button>
        <button v-if="session" class="button secondary compact new-task-button" :disabled="actionBusy" :aria-label="text('新任务', 'New task')" :title="text('新任务', 'New task')" @click="emit('newTask')"><Wrench :size="14" />{{ text('新任务', 'New task') }}</button>
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
    <div v-if="project && eventWarning" class="thread-stream-notice" role="status">
      <AlertTriangle :size="14" /><span>{{ eventWarning }}</span>
    </div>

    <section v-if="!project" class="thread-welcome">
      <div class="welcome-mark"><FlaskConical :size="24" /></div>
      <h2>{{ text('把下一件编程工作交给 Agent', 'Give the next coding task to the agent') }}</h2>
    </section>

    <section v-else-if="experiments.length" ref="turnList" class="turn-list" @scroll.passive="updateFollowState">
      <article
        v-for="experiment in experiments"
        :key="experiment.id"
        class="conversation-turn"
        :class="{ selected: experiment.id === selectedExperimentId, [`tone-${displayTone(experiment)}`]: true }"
        @click="selectTurn($event, experiment.id)"
      >
        <div class="turn-user">
          <span class="turn-label">{{ text('你', 'You') }}</span>
          <MarkdownContent class="task-markdown" :source="experiment.task" />
          <time :datetime="experiment.createdAt">{{ formatDate(experiment.createdAt) }}</time>
        </div>

        <div class="turn-agent">
          <div class="agent-avatar">O</div>
          <div class="agent-body">
            <div class="agent-meta">
              <strong>Offcanon</strong>
              <span class="turn-state" :class="displayTone(experiment)">{{ statusHeadline(experiment) }}</span>
              <button
                type="button"
                class="turn-select-button"
                :data-turn-select="experiment.id"
                :aria-pressed="experiment.id === selectedExperimentId ? 'true' : 'false'"
                :aria-label="text(`选择任务：${statusHeadline(experiment)}`, `Select task: ${statusHeadline(experiment)}`)"
                :title="text('选择此任务', 'Select this task')"
                @click.stop="emit('select', experiment.id)"
              >
                <CheckCircle2 v-if="experiment.id === selectedExperimentId" :size="15" />
                <CircleDot v-else :size="15" />
              </button>
            </div>
            <MarkdownContent v-if="experiment.agentSummary && (!workingStatuses.has(experiment.status) || awaitsVerification(experiment))" class="agent-summary" :source="experiment.agentSummary" />
            <p v-else-if="awaitsVerification(experiment)" class="agent-summary muted-copy">{{ statusDetail(experiment) }}</p>
            <p v-else-if="['FAILED', 'REJECTED', 'STALE', 'CANCELLED'].includes(experiment.status)" class="agent-summary failure-copy">{{ failureText(experiment) }}</p>
            <div v-else-if="workingStatuses.has(experiment.status)" class="working-row">
              <LoaderCircle class="spin" :size="16" /><span>{{ workingCopy(experiment) }}</span><small>{{ streamCopy() }}</small>
            </div>
            <p v-else class="agent-summary muted-copy">{{ statusDetail(experiment) }}</p>

            <RunActivityTimeline
              v-if="experiment.id === activityExperimentId && (activity.length || activityRunning(experiment))"
              :events="activity"
              :stream-state="streamState"
              :running="activityRunning(experiment)"
              :truncated="activityTruncated"
              :list-id="`run-activity-main-${experiment.id}`"
            />

            <div v-if="experiment.status === 'VERIFIED'" class="turn-decision">
              <div><CheckCircle2 :size="16" /><span>{{ text('已通过可信检查', 'Trusted checks passed') }}</span></div>
              <button class="button success compact" @click.stop="emit('review', experiment.id)"><Eye :size="14" />{{ text('查看变更', 'Review changes') }}</button>
            </div>
            <div v-else-if="experiment.status === 'PROMOTED'" class="turn-applied"><Check :size="15" /><span>{{ text('已应用到真实项目', 'Applied to the real project') }}</span><button class="text-button" @click.stop="emit('review', experiment.id)">{{ text('查看记录', 'View record') }}</button></div>
            <div v-else-if="experiment.status === 'READY_TO_RUN'" class="turn-actions">
              <button class="button primary compact" :disabled="actionBusy || !modelReady" @click.stop="emit('start', experiment.id)"><Play :size="14" />{{ text('开始', 'Start') }}</button>
              <button class="button danger-ghost compact" :disabled="actionBusy" @click.stop="emit('cancel', experiment.id)"><Pause :size="14" />{{ text('取消', 'Cancel') }}</button>
            </div>
            <div v-else-if="awaitsVerification(experiment)" class="turn-decision deferred-verification-actions">
              <div><AlertTriangle :size="16" /><span>{{ verificationPolicyChanged(experiment) ? text('验收命令已变化；这个封存结果仍然保留，需要按新命令重新验收。', 'Acceptance commands changed; this sealed result is retained and needs verification under the new policy.') : project?.verificationCommands.length ? text('结果已锁定，尚未运行验收。', 'The result is sealed and has not been verified yet.') : text('结果已锁定并保留；可继续迭代，验收命令稍后再设置。', 'The result is sealed and retained; you can continue and set acceptance commands later.') }}</span></div>
              <button v-if="project?.verificationCommands.length" class="button success compact" :disabled="actionBusy || blockedByOtherLifecycle(experiment)" :title="blockedByOtherLifecycle(experiment) ? text('同一任务已有后续操作正在进行', 'Another operation is active in this task') : undefined" @click.stop="emit('verify', experiment.id)"><ShieldCheck :size="14" />{{ text('运行验收', 'Run verification') }}</button>
              <button class="button secondary compact" :disabled="actionBusy || blockedByOtherLifecycle(experiment)" :title="blockedByOtherLifecycle(experiment) ? text('同一任务已有后续操作正在进行', 'Another operation is active in this task') : undefined" @click.stop="emit('editProject')"><Settings2 :size="14" />{{ project?.verificationCommands.length ? text('编辑项目设置', 'Edit project settings') : text('设置验收命令', 'Set acceptance commands') }}</button>
              <small v-if="blockedByOtherLifecycle(experiment)" class="turn-action-note">{{ text('同一任务已有后续操作正在进行。', 'Another operation is active in this task.') }}</small>
            </div>
            <div v-else-if="['FAILED', 'REJECTED', 'STALE', 'CANCELLED', 'RECOVERY_REQUIRED'].includes(experiment.status)" class="turn-recovery">
              <AlertTriangle :size="15" />
              <span>{{ failureText(experiment) }}</span>
              <button v-if="needsModelSettings(experiment)" class="button secondary compact" :disabled="actionBusy" @click.stop="emit('openSettings')">{{ text('去设置', 'Open Settings') }}</button>
              <button v-else-if="canRetry(experiment)" class="button secondary compact" :disabled="actionBusy || blockedByOtherLifecycle(experiment)" :title="blockedByOtherLifecycle(experiment) ? text('同一任务已有后续操作正在进行', 'A later operation is already active in this task') : undefined" @click.stop="emit('retry', experiment.id)">{{ text('再次运行', 'Retry') }}</button>
              <button v-if="canReverify(experiment) && project?.verificationCommands.length" class="button success compact" :disabled="actionBusy || blockedByOtherLifecycle(experiment)" :title="blockedByOtherLifecycle(experiment) ? text('同一任务已有后续操作正在进行', 'A later operation is already active in this task') : undefined" @click.stop="emit('verify', experiment.id)"><ShieldCheck :size="14" />{{ text('重新验收', 'Verify again') }}</button>
              <button v-if="canReverify(experiment) && !project?.verificationCommands.length" class="button secondary compact" :disabled="actionBusy || blockedByOtherLifecycle(experiment)" @click.stop="emit('editProject')"><Settings2 :size="14" />{{ text('设置验收命令', 'Set acceptance commands') }}</button>
              <button class="button secondary compact" :disabled="actionBusy" @click.stop="emit('review', experiment.id)">{{ text('查看原因', 'See why') }}</button>
              <small v-if="blockedByOtherLifecycle(experiment)" class="turn-action-note">{{ text('同一任务已有后续操作正在进行。', 'A later operation is already active in this task.') }}</small>
            </div>
            <div v-else-if="workingStatuses.has(experiment.status)" class="turn-actions">
              <button v-if="cancellableStatuses.has(experiment.status)" class="button danger-ghost compact" :disabled="actionBusy" @click.stop="emit('cancel', experiment.id)"><Pause :size="14" />{{ text('停止', 'Stop') }}</button>
              <span v-else class="muted-copy">{{ statusDetail(experiment) }}</span>
            </div>
            <div v-if="experiment.status === 'VERIFIED' && promotionPreview && experiment.id === selectedExperimentId && !promotionPreview.promotable" class="turn-note"><ShieldCheck :size="14" /><span>{{ text('结果可查看；应用条件会在审阅面板中说明。', 'The result is available; application conditions are shown in review.') }}</span></div>
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

    <div v-if="project && project.verificationCommands.length === 0" class="project-policy-gate" role="status">
      <AlertTriangle :size="16" />
      <div><strong>{{ text('尚未配置验收命令', 'No acceptance commands configured') }}</strong><span>{{ text('可以继续创建和迭代代码；配置验收命令后，实验结果才能通过验收并应用。', 'You can keep creating and iterating; configure acceptance commands before a result can pass verification and be applied.') }}</span></div>
      <button class="button secondary compact" :disabled="actionBusy" @click="emit('editProject')"><Settings2 :size="14" />{{ text('项目设置', 'Project settings') }}</button>
    </div>

    <footer class="composer-wrap">
      <form class="agent-composer" @submit.prevent="submit">
        <textarea ref="composer" v-model="draft" :placeholder="placeholder" :disabled="!project || !composerAvailable || actionBusy" rows="1" maxlength="20000" :aria-label="text('任务输入', 'Task input')" @input="resizeComposer" @keydown="onKeydown" />
        <div class="composer-bottom">
          <span class="composer-hint"><ShieldCheck :size="13" />{{ session?.title ?? text('新任务', 'New task') }}</span>
          <button type="submit" class="send-button" :disabled="!canSubmit" :aria-label="text('发送任务', 'Send task')" :title="text('发送任务', 'Send task')"><ArrowUp :size="17" /></button>
        </div>
      </form>
    </footer>
  </main>
</template>
