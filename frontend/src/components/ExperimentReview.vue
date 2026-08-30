<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  AlertTriangle,
  ArrowLeft,
  Ban,
  CheckCircle2,
  CircleDot,
  FileCheck2,
  FileDiff,
  FlaskConical,
  GitBranch,
  GitCommitHorizontal,
  LoaderCircle,
  RotateCcw,
  ShieldAlert,
  ShieldCheck,
  TerminalSquare,
} from 'lucide-vue-next'
import type {
  DiffEntry,
  Evidence,
  Experiment,
  Project,
  PromotionOutcome,
  PromotionPreview,
  PromotionReconcile,
  RunEvent,
} from '../api'
import { useLocale } from '../i18n'
import { formatDate, formatDuration, shortFingerprint, shortId, statusLabel, statusTone } from '../ui'
import MarkdownContent from './MarkdownContent.vue'

type ReviewTab = 'summary' | 'changes' | 'evidence' | 'activity'
type StreamState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline'

const props = defineProps<{
  project: Project | null
  experiment: Experiment | null
  diff: DiffEntry[]
  evidence: Evidence[]
  promotionPreview: PromotionPreview | null
  promotionOutcome: PromotionOutcome | null
  promotionReconcile: PromotionReconcile | null
  activity: RunEvent[]
  streamState: StreamState
  eventWarning: string | null
  actionBusy: boolean
  detailLoading: boolean
  detailError: string | null
  evidenceError: string | null
  diffError: string | null
  promotionPreviewError: string | null
}>()
const emit = defineEmits<{
  back: []
  start: []
  cancel: []
  promote: []
  reconcile: []
}>()
const { text } = useLocale()
const MAX_DEBUG_EVENT_CHARS = 20_000

const activeTab = ref<ReviewTab>('summary')
const tabs: Array<{ id: ReviewTab; zh: string; en: string }> = [
  { id: 'summary', zh: '摘要', en: 'Summary' },
  { id: 'changes', zh: '变更', en: 'Changes' },
  { id: 'evidence', zh: '证据', en: 'Evidence' },
  { id: 'activity', zh: '活动', en: 'Activity' },
]

const experimentVerificationEvidence = computed(() => props.evidence.filter((item) => item.kind === 'VERIFICATION'))
const promotionVerificationEvidence = computed(() => props.evidence.filter((item) => item.kind === 'PROMOTION_VERIFICATION'))
const integrityFailureText = computed(() => [
  props.experiment?.failureReason ?? '',
  props.promotionOutcome?.status ?? '',
  props.promotionOutcome?.detail ?? '',
].join(' ').toUpperCase())
const isIntegrityFailure = (item: Evidence) => item.kind !== 'AGENT_COMMAND'
  && !item.trusted
  && /(MUTATED_SOURCE|SOURCE_INTEGRITY|CANDIDATE_MUTATED|MUTATED)/.test(integrityFailureText.value)
const trustedEvidence = computed(() => experimentVerificationEvidence.value.filter((item) => item.trusted))
const failedVerificationEvidence = computed(() => experimentVerificationEvidence.value.filter((item) => !item.trusted && !isIntegrityFailure(item)))
const trustedPromotionEvidence = computed(() => promotionVerificationEvidence.value.filter((item) => item.trusted))
const failedPromotionEvidence = computed(() => promotionVerificationEvidence.value.filter((item) => !item.trusted && !isIntegrityFailure(item)))
const invalidatedEvidence = computed(() => props.evidence.filter(isIntegrityFailure))
const observations = computed(() => props.evidence.filter((item) => item.kind === 'AGENT_COMMAND'))
const passedTrustedEvidence = computed(() => trustedEvidence.value.filter(evidencePassed))
const passedPromotionEvidence = computed(() => trustedPromotionEvidence.value.filter(evidencePassed))
const totalAdditions = computed(() => props.diff.reduce((sum, item) => sum + item.additions, 0))
const totalDeletions = computed(() => props.diff.reduce((sum, item) => sum + item.deletions, 0))
const canonicalChanged = computed(() => props.experiment?.status === 'VERIFIED' && !!props.promotionPreview?.conflict)
const canonicalUnchanged = computed(() => props.experiment?.status === 'VERIFIED'
  && !props.detailLoading
  && !!props.promotionPreview?.baseFingerprint
  && props.promotionPreview.baseFingerprint === props.promotionPreview.currentFingerprint)
const recoveryRequired = computed(() => props.promotionPreview?.recoveryRequired
  || props.experiment?.status === 'RECOVERY_REQUIRED')
const promotionOutcomeDetail = computed(() => props.promotionOutcome?.status.startsWith('STALE')
  ? text(
      '主线已保留当前改动，这个实验没有写入真实项目。请在下方继续当前任务。',
      'Canonical kept its current changes and this experiment did not modify your project. Continue this task below.',
    )
  : props.promotionOutcome?.detail)
type FailurePresentation = { title: string; detail: string }

function failureCode(reason: string) {
  return reason.match(/^\s*([A-Z][A-Z0-9_]{2,})(?=\s*:|\s|$)/)?.[1] ?? null
}

const failurePresentation = computed<FailurePresentation | null>(() => {
  const experiment = props.experiment
  const reason = experiment?.failureReason?.trim() ?? ''
  if (!experiment || !['FAILED', 'REJECTED'].includes(experiment.status)) return null

  const code = failureCode(reason)
  if (code === 'MODEL_TRANSIENT_FAILURE') {
    return {
      title: text('模型服务暂时不可用', 'Model service temporarily unavailable'),
      detail: text(
        '本次实验未完成，真实项目未被修改。请在下方继续当前任务并稍后重试；如果问题持续，请检查设置中的模型连接。',
        'This experiment did not finish, and your canonical project was not modified. Continue this task below and retry later; if it persists, check the model connection in Settings.',
      ),
    }
  }
  if (code === 'AGENT_TIMEOUT' || code === 'MAX_STEPS_EXCEEDED' || code === 'TOOL_CALL_LIMIT_EXCEEDED') {
    return {
      title: text('实验运行未完成', 'Experiment did not finish'),
      detail: text(
        '实验运行达到限制，真实项目未被修改。可以缩小任务范围后在下方继续当前任务，或查看“活动”记录。',
        'The experiment reached its run limit, and your canonical project was not modified. Continue this task below with a smaller scope, or inspect Activity for details.',
      ),
    }
  }
  if (code && (code.startsWith('MODEL_') || code.startsWith('VERIFICATION_') || code.includes('POLICY') || code.includes('BLOCKED'))) {
    return {
      title: text('实验未能完成', 'Experiment could not complete'),
      detail: text(
        '运行遇到模型、验证或安全限制，真实项目未被修改。请查看“活动”记录，调整要求后在下方继续当前任务。',
        'The run hit a model, verification, or safety limitation, and your canonical project was not modified. Inspect Activity, adjust the request, and continue this task below.',
      ),
    }
  }
  if (experiment.status === 'REJECTED') {
    return {
      title: text('结果未应用到项目', 'Result was not applied'),
      detail: text(
        '本次结果没有进入项目主线，真实项目未被修改。请检查审阅信息，然后在下方继续当前任务进行修复。',
        'This result did not reach the project canonical, so your project was not modified. Review the recorded result, then continue this task below to fix it.',
      ),
    }
  }
  return {
    title: text('实验未完成', 'Experiment did not complete'),
    detail: text(
      '本次实验未能完成，真实项目未被修改。请查看“活动”记录了解经过，或在下方继续当前任务重试。',
      'This experiment did not complete, and your canonical project was not modified. Inspect Activity for what happened, or continue this task below to retry.',
    ),
  }
})

function applicationCondition(reason: string | null) {
  const normalized = reason?.trim().toLowerCase() ?? ''
  if (!normalized || normalized.includes('final candidate has not been sealed')) {
    return text('尚无可应用结果', 'No sealed result is ready to apply')
  }
  if (normalized.includes('trusted verification has not run')) {
    return text('可信检查尚未完成', 'Trusted checks have not run yet')
  }
  if (normalized.includes('trusted verification failed')) {
    return text('可信检查未通过，暂时无法应用', 'Trusted checks did not pass; changes cannot be applied yet')
  }
  if (normalized.includes('canonical changed after this experiment started')) {
    return text('项目主线已变化，不能直接应用这个实验', 'The project changed during this experiment; this result cannot be applied directly')
  }
  if (normalized.includes('earlier promotion requires recovery')) {
    return text('项目有待恢复的应用操作，请先完成恢复', 'A previous project update needs recovery before another one can be applied')
  }
  if (normalized.includes('candidate is already canonical')) {
    return text('该结果已经在项目主线中', 'This result is already in the project canonical')
  }
  if (normalized.includes('base snapshot is not available')) {
    return text('实验基线不可用，暂时无法应用', 'The experiment baseline is unavailable')
  }
  if (normalized.includes('canonical fingerprint unavailable')) {
    return text('暂时无法读取项目当前状态', 'The current project state could not be read')
  }
  return text('当前结果还不能应用', 'This result is not ready to apply')
}

const applicationConditionText = computed(() => {
  const preview = props.promotionPreview
  if (!preview) return null
  if (recoveryRequired.value) {
    return text('请先恢复项目中的未完成应用操作', 'Reconcile the unresolved project application first')
  }
  return preview.promotable
    ? text('满足应用条件，可以安全应用', 'Application conditions met; ready to apply')
    : applicationCondition(preview.blockingReason)
})

const allDetailUnavailable = computed(() => !props.detailLoading
  && !props.evidence.length
  && !props.diff.length
  && !props.promotionPreview
  && !!props.evidenceError
  && !!props.diffError
  && !!props.promotionPreviewError)

const receiptFingerprint = computed(() => props.promotionOutcome?.fingerprint
  ?? props.promotionPreview?.currentFingerprint
  ?? null)
const receiptFiles = computed(() => props.promotionOutcome?.changedFiles.length ?? props.diff.length)
const canCancel = computed(() => props.experiment
  ? ['READY_TO_RUN', 'RUNNING', 'AGENT_COMPLETED', 'VERIFYING'].includes(props.experiment.status)
  : false)

function streamLabel(value: StreamState) {
  const labels: Record<StreamState, [string, string]> = {
    idle: ['空闲', 'idle'],
    connecting: ['连接中', 'connecting'],
    live: ['已连接', 'live'],
    reconnecting: ['重连中', 'reconnecting'],
    offline: ['离线', 'offline'],
  }
  const [zh, en] = labels[value]
  return text(zh, en)
}

function eventLabel(event: RunEvent) {
  const labels: Record<string, [string, string]> = {
    EXPERIMENT_STARTED: ['已启动隔离实验', 'Isolated experiment started'],
    MODEL_REQUEST: ['正在思考下一步', 'Planning the next step'],
    MODEL_RESPONSE: ['已决定下一步', 'Next step chosen'],
    TOOL_CALL: ['正在调用工具', 'Calling a tool'],
    TOOL_RESULT: ['已完成一次操作', 'Completed an operation'],
    CONTEXT_SNAPSHOT: ['正在整理上下文', 'Preparing context'],
    CONTEXT_COMPACTED: ['正在压缩上下文', 'Compacting context'],
    MODEL_RETRY: ['模型暂时不可用，稍后重试', 'Model unavailable; retrying shortly'],
    SESSION_CONTEXT_IMPORTED: ['已承接上一轮任务上下文', 'Previous task context carried forward'],
    RUN_CONFIGURATION_RESOLVED: ['已固定本次运行配置', 'Run configuration fixed for this experiment'],
    TASK_MEMORY_AGENT_PROPOSAL_RECORDED: ['已保存一条待确认记忆', 'Saved a memory proposal'],
    TASK_MEMORY_VERIFIED_FACT_RECORDED: ['已记录可信验证事实', 'Recorded a verified fact'],
    TASK_MEMORY_UNAVAILABLE: ['记忆暂时不可用，继续使用当前代码', 'Memory unavailable; continuing with current code'],
    TASK_MEMORY_RECORD_FAILED: ['记忆未保存，运行结果不受影响', 'Memory was not saved; run result is unaffected'],
    AGENT_COMPLETED: ['代理已完成，正在保存结果', 'Agent finished; saving the result'],
    EXPERIMENT_RECOVERED: ['已恢复中断的实验', 'Interrupted experiment recovered'],
    RESULT_SNAPSHOT_SEALED: ['已锁定实验结果', 'Result sealed in the experiment'],
    VERIFICATION_STARTED: ['正在运行验证', 'Running verification'],
    VERIFICATION_FINISHED: ['验证已完成', 'Verification finished'],
    EXPERIMENT_FAILED: ['实验运行失败', 'Experiment run failed'],
    EXPERIMENT_CANCELLED: ['实验已取消', 'Experiment cancelled'],
    PROMOTION_PREPARING: ['正在准备应用', 'Preparing to apply the result'],
    PROMOTION_VERIFICATION_STARTED: ['正在检查应用候选', 'Checking the application candidate'],
    PROMOTION_BLOCKED: ['应用被阻止', 'Application blocked'],
    PROMOTION_RECOVERY_REQUIRED: ['需要恢复应用状态', 'Application recovery required'],
    PROMOTION_RECOVERY_DEFERRED: ['应用恢复已延期', 'Application recovery deferred'],
    PROMOTION_RECOVERED: ['应用状态已恢复', 'Application state recovered'],
    PROMOTION_MANUALLY_RECONCILED: ['应用状态已手动确认', 'Application state reconciled'],
    PROMOTED: ['已应用到主线', 'Applied to canonical'],
  }
  const pair = labels[event.type] ?? [statusLabel(event.type), statusLabel(event.type)]
  return text(pair[0], pair[1])
}

function evidencePassed(item: Evidence) {
  return item.exitCode === 0 && !item.timedOut && !item.cancelled
}

function evidenceFailureText(item: Evidence) {
  if (item.cancelled) return text('已取消', 'cancelled')
  if (item.timedOut) return text('超时', 'timed out')
  if (item.exitCode !== 0) return text(`退出码 ${item.exitCode}`, `exit ${item.exitCode}`)
  return text('未通过可信条件', 'did not satisfy trust conditions')
}

function eventPayload(event: RunEvent) {
  const serialized = JSON.stringify(event.payload, null, 2)
  if (serialized.length <= MAX_DEBUG_EVENT_CHARS) return serialized
  const marker = `\n... ${text('调试内容已截断；完整结果保留在事件记录中。', 'debug payload truncated here; the full result remains in the event record')} ...\n`
  const available = MAX_DEBUG_EVENT_CHARS - marker.length
  if (available <= 0) return serialized.slice(0, MAX_DEBUG_EVENT_CHARS)
  const head = Math.floor(available / 2)
  return serialized.slice(0, head) + marker + serialized.slice(serialized.length - (available - head))
}

function activateWithKeyboard(event: KeyboardEvent, index: number) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0
    : event.key === 'End' ? tabs.length - 1
      : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
  activeTab.value = tabs[next].id
  document.getElementById(`review-tab-${tabs[next].id}`)?.focus()
}
</script>

<template>
  <main class="review-surface" :aria-label="text('实验审阅', 'Experiment review')">
    <template v-if="experiment">
      <header class="review-header">
        <button class="icon-button mobile-only" :aria-label="text('返回实验', 'Back to experiments')" :title="text('实验', 'Experiments')" @click="emit('back')"><ArrowLeft :size="18" /></button>
        <div class="review-title">
          <p class="eyebrow">{{ project?.name }} / EXP-{{ shortId(experiment.id) }}</p>
          <MarkdownContent class="review-task-markdown" :source="experiment.task" />
        </div>
        <span class="status-badge prominent" :class="canonicalChanged ? 'warning' : statusTone(experiment.status)">{{ canonicalChanged ? text('主线已变化', 'Canonical changed') : statusLabel(experiment.status) }}</span>
      </header>

      <div class="decision-bar" :class="canonicalChanged ? 'warning' : statusTone(experiment.status)">
        <div class="decision-icon">
          <AlertTriangle v-if="canonicalChanged" :size="20" />
          <CheckCircle2 v-else-if="experiment.status === 'VERIFIED' || experiment.status === 'PROMOTED'" :size="20" />
          <AlertTriangle v-else-if="['RECOVERY_REQUIRED', 'STALE', 'FAILED', 'REJECTED'].includes(experiment.status)" :size="20" />
          <LoaderCircle v-else-if="['CREATED', 'SNAPSHOTTING', 'RUNNING', 'AGENT_COMPLETED', 'VERIFYING', 'PREPARING_PROMOTION', 'PROMOTING'].includes(experiment.status)" class="spin" :size="20" />
          <FlaskConical v-else :size="20" />
        </div>
        <div class="decision-copy">
           <strong v-if="recoveryRequired">{{ text('主线状态需要恢复。', 'Canonical state needs reconciliation.') }}</strong>
           <strong v-else-if="canonicalChanged">{{ text('项目主线已变化，这个实验不能直接应用。', 'Canonical changed, so this experiment cannot be applied directly.') }}</strong>
          <strong v-else-if="experiment.status === 'VERIFIED' && detailLoading">{{ text('实验结果已验证，正在检查项目主线。', 'Experiment result verified. Checking canonical now.') }}</strong>
          <strong v-else-if="allDetailUnavailable">{{ text('审阅数据加载失败。', 'Review data could not be loaded.') }}</strong>
          <strong v-else-if="experiment.status === 'VERIFIED' && !promotionPreview">{{ text('实验结果已验证，暂时无法确认项目主线。', 'Experiment result verified. Canonical could not be confirmed.') }}</strong>
          <strong v-else-if="canonicalUnchanged">{{ text('实验结果已验证，主线尚未改变。', 'Experiment result verified. Canonical is still unchanged.') }}</strong>
          <strong v-else-if="experiment.status === 'VERIFIED'">{{ text('实验结果已验证，当前还不能应用。', 'Experiment result verified. It cannot be applied yet.') }}</strong>
          <strong v-else-if="experiment.status === 'PROMOTED'">{{ text('实验结果已应用到主线。', 'Experiment result applied to canonical.') }}</strong>
          <strong v-else-if="experiment.status === 'RECOVERY_REQUIRED'">{{ text('主线状态需要恢复。', 'Canonical state needs reconciliation.') }}</strong>
          <strong v-else-if="experiment.status === 'STALE'">{{ text('这个实验基于旧主线。', 'This experiment is based on an older canonical state.') }}</strong>
          <strong v-else-if="experiment.status === 'READY_TO_RUN'">{{ text('隔离工作区已准备好。', 'Isolated workspace is ready.') }}</strong>
          <strong v-else-if="experiment.status === 'CREATED' || experiment.status === 'SNAPSHOTTING'">{{ text('正在准备隔离工作区。', 'Preparing the isolated workspace.') }}</strong>
          <strong v-else-if="experiment.status === 'AGENT_COMPLETED'">{{ text('代理已完成，正在保存结果。', 'Agent finished; saving the result.') }}</strong>
          <strong v-else-if="experiment.status === 'PREPARING_PROMOTION'">{{ text('正在准备应用。', 'Preparing to apply the result.') }}</strong>
          <strong v-else-if="experiment.status === 'PROMOTING'">{{ text('正在应用到主线。', 'Applying the result to canonical.') }}</strong>
          <strong v-else-if="failurePresentation">{{ failurePresentation.title }}</strong>
          <strong v-else>{{ statusLabel(experiment.status) }}</strong>
           <span v-if="recoveryRequired">{{ text('上一笔应用操作未闭合。请先恢复项目状态，恢复完成后再继续审阅。', 'An earlier application is unresolved. Reconcile the project state before reviewing this result.') }}</span>
           <span v-else-if="canonicalChanged">{{ text('真实项目保留当前改动；确认后会将这个实验标记为已过期。', 'Your project keeps its current changes; confirm to mark this experiment out of date.') }}</span>
          <span v-else-if="experiment.status === 'VERIFIED' && detailLoading">{{ text('正在加载实验差异和可信证据。', 'Loading the experiment diff and trusted evidence.') }}</span>
          <span v-else-if="allDetailUnavailable">{{ detailError }}</span>
          <span v-else-if="experiment.status === 'VERIFIED' && !promotionPreview">{{ text('无法读取应用条件，请刷新后重试。', 'Application conditions could not be loaded. Refresh and try again.') }}</span>
          <span v-else-if="experiment.status === 'VERIFIED'">{{ text(`应用前请审阅 ${diff.length} 个变更文件和 ${trustedEvidence.length} 条可信检查。`, `Review ${diff.length} changed files and ${trustedEvidence.length} trusted checks before applying.`) }}</span>
          <span v-else-if="experiment.status === 'PROMOTED'">{{ text('本次审阅包含应用回执。', 'An application receipt is available in this review.') }}</span>
          <span v-else-if="experiment.status === 'RECOVERY_REQUIRED'">{{ text('检查记录状态，然后执行受保护的恢复操作。', 'Inspect the recorded state, then run the guarded reconciliation.') }}</span>
          <span v-else-if="experiment.status === 'STALE'">{{ text('真实项目未被这个实验修改；请在下方基于最新主线继续当前任务。', 'This experiment did not modify your project; continue this task below from the latest canonical state.') }}</span>
          <span v-else-if="experiment.status === 'CREATED' || experiment.status === 'SNAPSHOTTING'">{{ text('正在创建隔离工作区，主线仍受到保护。', 'The isolated workspace is being created; canonical remains protected.') }}</span>
          <span v-else-if="experiment.status === 'AGENT_COMPLETED'">{{ text('正在锁定实验结果，然后开始可信验证。', 'The experiment result is being sealed before trusted verification starts.') }}</span>
          <span v-else-if="experiment.status === 'PREPARING_PROMOTION'">{{ text('主线仍受到保护，正在准备受保护的应用。', 'Canonical remains protected while the guarded application is prepared.') }}</span>
          <span v-else-if="experiment.status === 'PROMOTING'">{{ text('正在完成主线更新，请稍候。', 'The canonical update is being completed; please wait.') }}</span>
          <span v-else-if="failurePresentation">{{ failurePresentation.detail }}</span>
          <span v-else>{{ text('实验推进期间，主线始终受到保护。', 'Canonical remains protected while this experiment advances.') }}</span>
        </div>
        <div class="decision-actions">
          <button v-if="experiment.status === 'READY_TO_RUN'" class="button primary" :disabled="actionBusy" @click="emit('start')"><LoaderCircle :class="{ spin: actionBusy }" :size="16" /> {{ text('启动代理', 'Start agent') }}</button>
           <button v-if="experiment.status === 'VERIFIED'" class="button" :class="canonicalChanged ? 'warning' : promotionPreview?.promotable ? 'success' : 'secondary'" :disabled="actionBusy || recoveryRequired || !!promotionPreviewError || (!promotionPreview?.promotable && !promotionPreview?.conflict)" @click="emit('promote')">
            <AlertTriangle v-if="canonicalChanged" :size="16" />
            <GitCommitHorizontal v-else :size="16" />
            {{ canonicalChanged ? text('处理主线变化', 'Resolve canonical change') : text('审阅并应用', 'Review and apply') }}
          </button>
           <button v-if="recoveryRequired" class="button warning" :disabled="actionBusy" @click="emit('reconcile')"><RotateCcw :class="{ spin: actionBusy }" :size="16" /> {{ text('恢复状态', 'Reconcile state') }}</button>
          <button v-if="canCancel" class="button danger-ghost" :disabled="actionBusy" @click="emit('cancel')"><Ban :size="16" /> {{ text('取消', 'Cancel') }}</button>
        </div>
      </div>

      <nav class="review-tabs" role="tablist" :aria-label="text('实验审阅分区', 'Experiment review sections')">
        <button
          v-for="(tab, index) in tabs"
          :id="`review-tab-${tab.id}`"
          :key="tab.id"
          role="tab"
          :aria-selected="activeTab === tab.id"
          :aria-controls="`review-panel-${tab.id}`"
          :tabindex="activeTab === tab.id ? 0 : -1"
          @click="activeTab = tab.id"
          @keydown="activateWithKeyboard($event, index)"
        >
          {{ text(tab.zh, tab.en) }}
          <small v-if="tab.id === 'changes'">{{ detailLoading ? '...' : diffError ? '!' : diff.length }}</small>
          <small v-else-if="tab.id === 'evidence'">{{ detailLoading ? '...' : evidenceError ? '!' : experimentVerificationEvidence.length }}</small>
          <span v-else-if="tab.id === 'activity'" class="stream-dot" :class="streamState" :title="`${text('事件流', 'Event stream')} ${streamLabel(streamState)}`" />
        </button>
      </nav>

      <section v-show="activeTab === 'summary'" id="review-panel-summary" class="review-panel" role="tabpanel" aria-labelledby="review-tab-summary" tabindex="0">
        <div v-if="allDetailUnavailable" class="panel-empty detail-error-panel" role="alert">
          <ShieldAlert :size="24" /><strong>{{ text('无法加载审阅数据', 'Unable to load review data') }}</strong><span>{{ detailError }}</span>
        </div>
        <div v-else-if="detailLoading" class="review-loading" role="status">
          <LoaderCircle class="spin" :size="22" /><strong>{{ text('正在加载审阅证据', 'Loading review evidence') }}</strong><span>{{ text('正在读取实验差异、验证记录和应用条件。', 'Reading the experiment diff, verification records, and application conditions.') }}</span>
        </div>
        <template v-else>
        <div v-if="evidenceError || diffError || promotionPreviewError" class="detail-notice-list" role="status">
          <p v-if="evidenceError" class="inline-warning"><ShieldAlert :size="15" />{{ text('验证记录暂时不可用：', 'Verification records unavailable: ') }}{{ evidenceError }}</p>
          <p v-if="diffError" class="inline-warning"><ShieldAlert :size="15" />{{ text('变更差异暂时不可用：', 'Experiment diff unavailable: ') }}{{ diffError }}</p>
          <p v-if="promotionPreviewError" class="inline-warning"><ShieldAlert :size="15" />{{ text('应用条件暂时不可用：', 'Application conditions unavailable: ') }}{{ promotionPreviewError }}</p>
        </div>
        <div v-if="experiment.status === 'PROMOTED'" class="receipt-band">
          <span class="receipt-icon"><ShieldCheck :size="22" /></span>
          <div><p class="eyebrow">{{ text('应用回执', 'APPLICATION RECEIPT') }}</p><h3>{{ text('实验结果已进入主线', 'Experiment result applied to canonical') }}</h3><span>{{ receiptFiles }} {{ text('个文件已在可信验证后应用。', receiptFiles === 1 ? 'file applied after trusted verification.' : 'files applied after trusted verification.') }}</span></div>
          <dl><div><dt>{{ text('结果', 'Result') }}</dt><dd><code>{{ shortFingerprint(receiptFingerprint) }}</code></dd></div><div><dt>{{ text('快照', 'Snapshot') }}</dt><dd><code>{{ shortId(experiment.resultSnapshotId, 12) }}</code></dd></div></dl>
        </div>

        <div v-if="promotionReconcile" class="outcome-band warning">
          <RotateCcw :size="18" /><div><strong>{{ statusLabel(promotionReconcile.journalPhase) }}</strong><span>{{ promotionReconcile.detail }}</span></div><code>{{ shortFingerprint(promotionReconcile.fingerprint) }}</code>
        </div>
        <div v-if="promotionOutcome && experiment.status !== 'PROMOTED'" class="outcome-band" :class="promotionOutcome.promoted ? 'success' : 'danger'">
          <ShieldCheck v-if="promotionOutcome.promoted" :size="18" /><ShieldAlert v-else :size="18" />
          <div><strong>{{ statusLabel(promotionOutcome.status) }}</strong><span>{{ promotionOutcomeDetail }}</span></div>
        </div>
        <div v-if="failurePresentation && experiment.status !== 'RECOVERY_REQUIRED'" class="outcome-band danger">
          <ShieldAlert :size="18" /><div><strong>{{ failurePresentation.title }}</strong><span>{{ failurePresentation.detail }}</span></div>
        </div>

        <div class="summary-grid">
          <section class="summary-section agent-summary">
            <p class="section-label">{{ text('代理结论', 'AGENT CONCLUSION') }}</p>
            <MarkdownContent v-if="experiment.agentSummary" class="summary-text" :source="experiment.agentSummary" />
            <p v-else class="empty-copy">{{ text('代理尚未生成最终摘要。', 'The agent has not produced a final summary.') }}</p>
          </section>

          <section class="summary-section decision-evidence">
            <p class="section-label">{{ text('决策证据', 'DECISION EVIDENCE') }}</p>
            <dl class="metric-list">
              <div><dt>{{ text('变更文件', 'Changed files') }}</dt><dd>{{ diff.length }} <span>+{{ totalAdditions }} -{{ totalDeletions }}</span></dd></div>
              <div><dt>{{ text('实验验证', 'Experiment checks') }}</dt><dd :class="{ verified: experimentVerificationEvidence.length > 0 && passedTrustedEvidence.length === experimentVerificationEvidence.length }">{{ passedTrustedEvidence.length }} / {{ experimentVerificationEvidence.length }}</dd></div>
              <div><dt>{{ text('应用候选验证', 'Promotion checks') }}</dt><dd :class="{ verified: promotionVerificationEvidence.length > 0 && passedPromotionEvidence.length === promotionVerificationEvidence.length }">{{ passedPromotionEvidence.length }} / {{ promotionVerificationEvidence.length }}</dd></div>
              <div><dt>{{ text('未通过检查', 'Failed checks') }}</dt><dd :class="{ danger: failedVerificationEvidence.length > 0 || failedPromotionEvidence.length > 0 }">{{ failedVerificationEvidence.length + failedPromotionEvidence.length }}</dd></div>
              <div><dt>{{ text('源完整性失效', 'Integrity failures') }}</dt><dd :class="{ danger: invalidatedEvidence.length > 0 }">{{ invalidatedEvidence.length }}</dd></div>
              <div><dt>{{ text('代理观察', 'Agent observations') }}</dt><dd>{{ observations.length }}</dd></div>
            </dl>
          </section>

          <section class="summary-section change-locations">
            <p class="section-label">{{ text('改动位置', 'CHANGE LOCATIONS') }}</p>
            <div class="world-line"><span class="world-icon experiment"><FlaskConical :size="16" /></span><div><strong>{{ text('实验', 'Experiment') }}</strong><code :title="experiment.workspacePath ?? ''">{{ experiment.workspacePath ?? text('尚未物化', 'not materialized') }}</code></div></div>
            <div class="world-divider"><span>{{ text('应用条件', 'application conditions') }}</span></div>
            <div class="world-line"><span class="world-icon canonical"><GitBranch :size="16" /></span><div><strong>{{ text('主线', 'Canonical') }}</strong><code :title="project?.canonicalPath">{{ project?.canonicalPath }}</code></div></div>
          </section>

          <section v-if="promotionPreview && experiment.status !== 'PROMOTED'" class="summary-section fingerprint-section">
            <p class="section-label">{{ text('应用条件', 'APPLICATION CONDITIONS') }}</p>
            <dl class="fingerprint-list">
              <div><dt>{{ text('基线', 'Base') }}</dt><dd><code :title="promotionPreview.baseFingerprint ?? ''">{{ shortFingerprint(promotionPreview.baseFingerprint) }}</code></dd></div>
              <div><dt>{{ text('当前主线', 'Canonical now') }}</dt><dd><code :title="promotionPreview.currentFingerprint ?? ''">{{ shortFingerprint(promotionPreview.currentFingerprint) }}</code></dd></div>
              <div><dt>{{ text('实验结果', 'Experiment result') }}</dt><dd><code :title="promotionPreview.finalCandidateFingerprint ?? ''">{{ shortFingerprint(promotionPreview.finalCandidateFingerprint) }}</code></dd></div>
            </dl>
             <div class="gate-status" :class="recoveryRequired ? 'danger' : promotionPreview.promotable ? 'success' : promotionPreview.conflict ? 'danger' : 'neutral'">
               <ShieldCheck v-if="promotionPreview.promotable" :size="16" />
               <ShieldAlert v-else-if="recoveryRequired || promotionPreview.conflict" :size="16" />
              <CircleDot v-else :size="16" />
              <span>{{ applicationConditionText }}</span>
            </div>
          </section>
        </div>
        </template>
      </section>

      <section v-show="activeTab === 'changes'" id="review-panel-changes" class="review-panel" role="tabpanel" aria-labelledby="review-tab-changes" tabindex="0">
        <header class="panel-heading"><div><p class="section-label">{{ text('实验结果差异', 'EXPERIMENT RESULT DIFF') }}</p><h3>{{ detailLoading ? text('正在加载实验差异', 'Loading experiment diff') : text(`${diff.length} 个变更文件`, `${diff.length} changed files`) }}</h3></div><span v-if="!detailLoading && !diffError" class="diff-totals"><strong>+{{ totalAdditions }}</strong><em>-{{ totalDeletions }}</em></span></header>
        <div v-if="diffError" class="panel-empty detail-error-panel" role="alert"><ShieldAlert :size="24" /><strong>{{ text('无法加载变更', 'Unable to load changes') }}</strong><span>{{ diffError }}</span></div>
        <div v-else-if="detailLoading" class="review-loading compact" role="status"><LoaderCircle class="spin" :size="20" /><strong>{{ text('正在加载变更', 'Loading changes') }}</strong></div>
        <div v-else-if="diff.length" class="diff-list">
          <details v-for="item in diff" :key="item.path" class="diff-entry">
            <summary>
              <span class="diff-kind" :class="item.change.toLowerCase()">{{ item.change === 'MODIFIED' ? 'M' : item.change === 'ADDED' ? '+' : '-' }}</span>
              <code>{{ item.path }}</code>
              <span>{{ item.binary ? text('二进制', 'binary') : `+${item.additions} -${item.deletions}` }}</span>
            </summary>
            <pre v-if="item.patch" class="code-output diff-patch">{{ item.patch }}</pre>
            <p v-else class="empty-copy">{{ text('没有可用的文本补丁。', 'No textual patch available.') }}</p>
          </details>
        </div>
        <div v-else class="panel-empty"><FileDiff :size="24" /><strong>{{ text('没有实验变更', 'No experiment changes') }}</strong><span>{{ text('Agent 尚未生成代码差异。', 'The agent has not produced a code diff.') }}</span></div>
      </section>

      <section v-show="activeTab === 'evidence'" id="review-panel-evidence" class="review-panel" role="tabpanel" aria-labelledby="review-tab-evidence" tabindex="0">
        <header class="panel-heading"><div><p class="section-label">{{ text('可信验证', 'TRUSTED VERIFICATION') }}</p><h3>{{ detailLoading ? text('正在加载验证记录', 'Loading verification records') : text(`${passedTrustedEvidence.length} / ${experimentVerificationEvidence.length} 条实验检查通过`, `${passedTrustedEvidence.length} of ${experimentVerificationEvidence.length} experiment checks passed`) }}</h3></div><ShieldCheck :class="experimentVerificationEvidence.length > 0 && passedTrustedEvidence.length === experimentVerificationEvidence.length ? 'text-success' : 'text-muted'" :size="22" /></header>
        <div v-if="evidenceError" class="panel-empty detail-error-panel" role="alert"><ShieldAlert :size="24" /><strong>{{ text('无法加载验证记录', 'Unable to load verification records') }}</strong><span>{{ evidenceError }}</span></div>
        <div v-else-if="detailLoading" class="review-loading compact" role="status"><LoaderCircle class="spin" :size="20" /><strong>{{ text('正在加载证据', 'Loading evidence') }}</strong></div>
        <template v-else>
          <div v-if="experimentVerificationEvidence.length" class="evidence-group">
            <div class="subsection-heading"><FileCheck2 :size="16" /><div><strong>{{ text('实验结果验证', 'Experiment result verification') }}</strong><span>{{ text('用于判断实验结果是否通过可信检查。', 'Checks whether the experiment result passed trusted verification.') }}</span></div></div>
            <div v-if="trustedEvidence.length" class="evidence-list">
              <details v-for="item in trustedEvidence" :key="item.id" class="evidence-entry">
                <summary><span class="evidence-result success"><CheckCircle2 :size="16" /></span><span><code>{{ item.command }}</code><small>{{ item.environmentProfile }} · {{ text('通过', 'passed') }} · {{ formatDuration(item.durationMillis) }} · {{ text('快照', 'snapshot') }} {{ shortId(item.snapshotId) }}</small></span></summary>
                <div class="evidence-detail"><dl><div><dt>{{ text('工作目录', 'Working directory') }}</dt><dd><code>{{ item.cwd }}</code></dd></div><div><dt>{{ text('完成时间', 'Completed') }}</dt><dd>{{ formatDate(item.completedAt) }}</dd></div></dl><pre v-if="item.stdout" class="code-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="code-output stderr">{{ item.stderr }}</pre></div>
              </details>
            </div>
            <div v-if="failedVerificationEvidence.length" class="evidence-list">
              <details v-for="item in failedVerificationEvidence" :key="item.id" class="evidence-entry invalidated">
                <summary><span class="evidence-result danger"><ShieldAlert :size="16" /></span><span><code>{{ item.command }}</code><small>{{ evidenceFailureText(item) }} · {{ item.environmentProfile }} · {{ formatDuration(item.durationMillis) }}</small></span></summary>
                <div class="evidence-detail"><dl><div><dt>{{ text('工作目录', 'Working directory') }}</dt><dd><code>{{ item.cwd }}</code></dd></div><div><dt>{{ text('完成时间', 'Completed') }}</dt><dd>{{ formatDate(item.completedAt) }}</dd></div></dl><pre v-if="item.stdout" class="code-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="code-output stderr">{{ item.stderr }}</pre></div>
              </details>
            </div>
          </div>
          <div v-else class="panel-empty"><FileCheck2 :size="24" /><strong>{{ text('暂无实验验证记录', 'No experiment verification yet') }}</strong><span>{{ text('实验结果生成后才会运行验证。', 'Verification runs after the experiment result is ready.') }}</span></div>

          <div v-if="promotionVerificationEvidence.length" class="evidence-group">
            <div class="subsection-heading"><ShieldCheck :size="16" /><div><strong>{{ text('应用候选验证', 'Promotion candidate verification') }}</strong><span>{{ text('应用前会在一次性候选工作区再次运行验证。', 'The candidate is verified again in a disposable workspace before application.') }}</span></div></div>
            <div v-if="trustedPromotionEvidence.length" class="evidence-list">
              <details v-for="item in trustedPromotionEvidence" :key="item.id" class="evidence-entry">
                <summary><span class="evidence-result success"><CheckCircle2 :size="16" /></span><span><code>{{ item.command }}</code><small>{{ item.environmentProfile }} · {{ text('通过', 'passed') }} · {{ formatDuration(item.durationMillis) }} · {{ text('快照', 'snapshot') }} {{ shortId(item.snapshotId) }}</small></span></summary>
                <div class="evidence-detail"><dl><div><dt>{{ text('工作目录', 'Working directory') }}</dt><dd><code>{{ item.cwd }}</code></dd></div><div><dt>{{ text('完成时间', 'Completed') }}</dt><dd>{{ formatDate(item.completedAt) }}</dd></div></dl><pre v-if="item.stdout" class="code-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="code-output stderr">{{ item.stderr }}</pre></div>
              </details>
            </div>
            <div v-if="failedPromotionEvidence.length" class="evidence-list">
              <details v-for="item in failedPromotionEvidence" :key="item.id" class="evidence-entry invalidated">
                <summary><span class="evidence-result danger"><ShieldAlert :size="16" /></span><span><code>{{ item.command }}</code><small>{{ evidenceFailureText(item) }} · {{ item.environmentProfile }} · {{ formatDuration(item.durationMillis) }}</small></span></summary>
                <div class="evidence-detail"><dl><div><dt>{{ text('工作目录', 'Working directory') }}</dt><dd><code>{{ item.cwd }}</code></dd></div><div><dt>{{ text('完成时间', 'Completed') }}</dt><dd>{{ formatDate(item.completedAt) }}</dd></div></dl><pre v-if="item.stdout" class="code-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="code-output stderr">{{ item.stderr }}</pre></div>
              </details>
            </div>
          </div>
        </template>

        <template v-if="invalidatedEvidence.length">
          <div class="subsection-heading danger"><ShieldAlert :size="16" /><div><strong>{{ text('源完整性失效', 'Source integrity invalidated') }}</strong><span>{{ text('这些记录对应的工作区在验证期间发生了不应有的改动，不能作为应用依据。', 'These records came from a workspace that changed during verification and cannot authorize an application.') }}</span></div></div>
          <details v-for="item in invalidatedEvidence" :key="item.id" class="evidence-entry invalidated">
            <summary><span class="evidence-result danger"><ShieldAlert :size="16" /></span><span><code>{{ item.command }}</code><small>{{ text('源完整性失效', 'source integrity failed') }} · {{ text('退出码', 'exit') }} {{ item.exitCode }} · {{ text('快照', 'snapshot') }} {{ shortId(item.snapshotId) }}</small></span></summary>
            <div class="evidence-detail"><pre v-if="item.stdout" class="code-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="code-output stderr">{{ item.stderr }}</pre></div>
          </details>
        </template>

        <template v-if="observations.length">
          <div class="subsection-heading"><TerminalSquare :size="16" /><div><strong>{{ text('代理观察', 'Agent observations') }}</strong><span>{{ text('可作为上下文参考，但永远不是应用依据。', 'Useful context, never application authority.') }}</span></div></div>
          <details v-for="item in observations" :key="item.id" class="evidence-entry observation">
            <summary><span class="evidence-result neutral"><TerminalSquare :size="16" /></span><span><code>{{ item.command }}</code><small>{{ text('仅观察', 'observation only') }} · {{ text('退出码', 'exit') }} {{ item.exitCode }} · {{ formatDuration(item.durationMillis) }}</small></span></summary>
            <div class="evidence-detail"><pre v-if="item.stdout" class="code-output">{{ item.stdout }}</pre><pre v-if="item.stderr" class="code-output stderr">{{ item.stderr }}</pre></div>
          </details>
        </template>
      </section>

      <section v-show="activeTab === 'activity'" id="review-panel-activity" class="review-panel" role="tabpanel" aria-labelledby="review-tab-activity" tabindex="0">
        <header class="panel-heading"><div><p class="section-label">{{ text('运行活动', 'RUN ACTIVITY') }}</p><h3>{{ text(`${activity.length} 条保留事件`, `${activity.length} retained events`) }}</h3></div><span class="connection-label" :class="streamState"><span class="stream-dot" :class="streamState" />{{ streamLabel(streamState) }}</span></header>
        <p v-if="eventWarning" class="inline-warning" role="status"><AlertTriangle :size="15" />{{ eventWarning }}</p>
        <ol v-if="activity.length" class="activity-list">
          <li v-for="event in activity" :key="event.eventId || event.sequence">
            <span class="activity-sequence">{{ String(event.sequence).padStart(3, '0') }}</span>
            <span class="activity-marker" />
            <div><strong>{{ eventLabel(event) }}</strong><time :datetime="event.timestamp">{{ formatDate(event.timestamp) }}</time></div>
          </li>
        </ol>
        <div v-else class="panel-empty"><CircleDot :size="24" /><strong>{{ text('尚未收到活动', 'No activity received') }}</strong><span>{{ text('打开此实验期间，事件流会自动重连。', 'The stream reconnects automatically while this experiment is open.') }}</span></div>

        <details v-if="activity.length" class="debug-events">
          <summary>{{ text('调试事件内容', 'Debug event payloads') }}</summary>
          <div v-for="event in activity" :key="`debug-${event.eventId || event.sequence}`"><span>#{{ event.sequence }} {{ event.type }}</span><pre class="code-output">{{ eventPayload(event) }}</pre></div>
        </details>
      </section>
    </template>

    <div v-else class="pane-empty review-empty">
      <ShieldCheck :size="27" />
      <strong>{{ text('选择一个实验进行审阅', 'Select an experiment to review') }}</strong>
      <span>{{ text('主线更新前，变更和可信证据会显示在这里。', 'Changes and trusted evidence appear here before canonical can be updated.') }}</span>
    </div>
  </main>
</template>
