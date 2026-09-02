<script setup lang="ts">
import { computed, onUnmounted, ref, useId, watch } from 'vue'
import {
  AlertTriangle,
  BrainCircuit,
  Check,
  ChevronDown,
  ChevronUp,
  CircleDot,
  Database,
  FilePenLine,
  FileSearch,
  FileText,
  GitCommitHorizontal,
  ListTree,
  LoaderCircle,
  MemoryStick,
  ShieldCheck,
  TerminalSquare,
  Trash2,
  Wrench,
  X,
} from 'lucide-vue-next'
import type { RunEvent } from '../api'
import { useLocale } from '../i18n'
import { runEventDefinition, runEventLabel } from '../runEvents'

type StreamState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline'
type EntryKind = 'thinking' | 'tool' | 'context' | 'lifecycle' | 'verification' | 'promotion' | 'memory'
type EntryTone = 'neutral' | 'active' | 'success' | 'warning' | 'danger'

type TimelineEntry = {
  key: string
  sequence: number
  timestamp: string
  kind: EntryKind
  tone: EntryTone
  title: string
  detail: string | null
  meta: string | null
  quiet: boolean
  pending: boolean
  modelState?: 'requesting' | 'responded' | 'interrupted'
  /** Logical model turn identity. `null` means a legacy event without step. */
  modelStep?: number | null
  modelRequestSequence?: number
  /** Set only when a MODEL_RESPONSE has actually been observed for this row. */
  modelResponseSequence?: number
  toolState?: 'queued' | 'running' | 'completed' | 'failed' | 'interrupted'
  /** Kept separately from the rendered title so legacy/new events can merge. */
  toolId?: string
  toolStep?: number | null
}

const props = defineProps<{
  events: RunEvent[]
  streamState: StreamState
  running: boolean
  truncated?: boolean
  /** Stable per-instance target for the expand/collapse control. */
  listId?: string
}>()

const { text } = useLocale()
const generatedListId = `run-activity-list-${useId().replace(/[^A-Za-z0-9_-]/g, '-')}`
const activityListId = computed(() => props.listId?.trim() || generatedListId)
const expanded = ref(false)
const announcement = ref('')
const now = ref(Date.now())
const COLLAPSED_ENTRIES = 7
let clockTimer: number | null = null

function syncClock() {
  if (typeof window === 'undefined') return
  if (props.running && clockTimer === null) {
    now.value = Date.now()
    clockTimer = window.setInterval(() => { now.value = Date.now() }, 1_000)
  } else if (!props.running && clockTimer !== null) {
    window.clearInterval(clockTimer)
    clockTimer = null
  }
}

watch(() => props.running, syncClock, { immediate: true })
onUnmounted(() => {
  if (clockTimer !== null && typeof window !== 'undefined') window.clearInterval(clockTimer)
  clockTimer = null
})

function payloadString(event: RunEvent, key: string) {
  const value = event.payload[key]
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function payloadNumber(event: RunEvent, key: string) {
  const value = event.payload[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function payloadBoolean(event: RunEvent, key: string) {
  return typeof event.payload[key] === 'boolean' ? event.payload[key] as boolean : null
}

function payloadArray(event: RunEvent, key: string) {
  return Array.isArray(event.payload[key]) ? event.payload[key] as unknown[] : []
}

function shortText(value: string | null, limit = 360) {
  if (!value) return null
  const compact = value.replace(/\r\n/g, '\n').trim()
  return compact.length > limit ? `${compact.slice(0, limit)}...` : compact
}

function stepOf(event: RunEvent): number | null {
  const value = payloadNumber(event, 'step')
  return value !== null && Number.isInteger(value) && value > 0 ? value : null
}

// Provider call ids are usually unique, but the protocol does not require
// that across model turns. Include the step so a reused id cannot merge two
// independent tool executions in the activity view.
function toolEntryKey(step: number | null, id: string) {
  const turn = step === null ? 'legacy' : String(step)
  return `tool-${turn}-${id}`
}

function modelEntryKey(step: number | null, sequence: number) {
  return step === null ? `model-legacy-${sequence}` : `model-${step}`
}

function toolPayload(event: RunEvent) {
  return {
    id: payloadString(event, 'toolCallId') ?? payloadString(event, 'id') ?? `sequence-${event.sequence}`,
    tool: payloadString(event, 'tool') ?? payloadString(event, 'name') ?? 'tool',
    path: payloadString(event, 'path'),
    query: payloadString(event, 'query'),
    command: payloadString(event, 'command'),
    contentChars: payloadNumber(event, 'contentChars'),
    outputChars: payloadNumber(event, 'outputChars'),
    outputTruncated: payloadBoolean(event, 'outputTruncated') === true,
  }
}

function parseModelToolCall(value: unknown, sequence: number) {
  if (!value || typeof value !== 'object') return null
  const call = value as Record<string, unknown>
  const id = typeof call.id === 'string' && call.id ? call.id : `model-${sequence}`
  const tool = typeof call.name === 'string' && call.name ? call.name : 'tool'
  let argumentsValue: Record<string, unknown> = {}
  if (call.arguments && typeof call.arguments === 'object' && !Array.isArray(call.arguments)) {
    argumentsValue = call.arguments as Record<string, unknown>
  } else if (typeof call.arguments === 'string') {
    try {
      const parsed = JSON.parse(call.arguments)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) argumentsValue = parsed
    } catch {
      // Older retained events may contain a truncated JSON argument string.
    }
  }
  return {
    id,
    tool,
    path: typeof argumentsValue.path === 'string' ? argumentsValue.path : null,
    query: typeof argumentsValue.query === 'string' ? argumentsValue.query : null,
    command: typeof argumentsValue.command === 'string' ? argumentsValue.command : null,
    contentChars: typeof argumentsValue.content === 'string' ? argumentsValue.content.length : null,
    outputChars: null,
    outputTruncated: false,
  }
}

function toolTitle(tool: ReturnType<typeof toolPayload>) {
  const target = tool.path ? ` ${tool.path}` : ''
  if (tool.tool === 'read_file') return text(`读取文件${target}`, `Read file${target}`)
  if (tool.tool === 'write_file') return text(`写入文件${target}`, `Write file${target}`)
  if (tool.tool === 'delete_file') return text(`删除文件${target}`, `Delete file${target}`)
  if (tool.tool === 'list_files') return text(`浏览目录${target}`, `List directory${target}`)
  if (tool.tool === 'search_files') return text(`搜索代码${target}`, `Search code${target}`)
  if (tool.tool === 'shell') return text('运行命令', 'Run command')
  return text('执行自定义工具', 'Run custom tool')
}

function toolDetail(tool: ReturnType<typeof toolPayload>) {
  if (tool.tool === 'shell') return shortText(tool.command, 500)
  if (tool.tool === 'search_files' && tool.query) return text(`查找“${shortText(tool.query, 160)}”`, `Find "${shortText(tool.query, 160)}"`)
  if (tool.tool === 'write_file' && tool.contentChars !== null) return text(`${tool.contentChars} 个字符`, `${tool.contentChars} characters`)
  return null
}

function toolLabel(tool: string) {
  if (tool === 'read_file') return text('读取文件', 'read file')
  if (tool === 'write_file') return text('写入文件', 'write file')
  if (tool === 'delete_file') return text('删除文件', 'delete file')
  if (tool === 'list_files') return text('浏览目录', 'list directory')
  if (tool === 'search_files') return text('搜索代码', 'search code')
  if (tool === 'shell') return text('运行命令', 'run command')
  return text('自定义工具', 'custom tool')
}

function contextCompactionLabel(kind: string | null) {
  if (kind === 'TOOL_CALL_ARGUMENTS') return text('工具参数', 'tool arguments')
  if (kind === 'TOOL_OBSERVATION') return text('工具输出', 'tool output')
  if (kind === 'ROLLING_SUMMARY') return text('滚动摘要', 'rolling summary')
  return text('上下文', 'context')
}

function lifecycleStatusLabel(status: string | null) {
  if (!status) return null
  const labels: Record<string, [string, string]> = {
    FAILED: ['失败', 'failed'],
    AGENT_COMPLETED: ['结果保留，待验收', 'result retained; awaiting verification'],
    VERIFIED: ['已通过验收', 'verified'],
    PROMOTED: ['已应用', 'applied'],
    REJECTED: ['验收未通过', 'checks failed'],
    STALE: ['已过期', 'out of date'],
    RECOVERY_REQUIRED: ['需要恢复', 'recovery required'],
    PROMOTION_VERIFICATION_FAILED: ['应用前验收未通过', 'pre-application checks failed'],
    PROMOTION_ABORTED: ['应用已中止', 'application aborted'],
  }
  const pair = labels[status]
  return pair ? text(pair[0], pair[1]) : text('状态已更新', 'status updated')
}

function eventDetail(event: RunEvent) {
  const step = stepOf(event)
  if (event.type === 'CONTEXT_SNAPSHOT') {
    const messages = payloadNumber(event, 'messageCount') ?? payloadArray(event, 'messages').length
    const chars = payloadNumber(event, 'contextChars')
    return text(`第 ${step ?? '?'} 步 · ${messages} 条消息${chars === null ? '' : ` · ${chars} 个字符`}`,
      `Step ${step ?? '?'} · ${messages} messages${chars === null ? '' : ` · ${chars} characters`}`)
  }
  if (event.type === 'CONTEXT_COMPACTED') {
    const kind = payloadString(event, 'kind')
    const remaining = payloadNumber(event, 'remainingChars') ?? payloadNumber(event, 'keptChars')
    const label = contextCompactionLabel(kind)
    return text(`${label}${remaining === null ? '' : ` · 保留 ${remaining} 个字符`}`,
      `${label}${remaining === null ? '' : ` · ${remaining} characters kept`}`)
  }
  if (event.type === 'RUN_CONFIGURATION_RESOLVED') {
    const model = payloadString(event, 'modelName')
    const maxSteps = payloadNumber(event, 'maxSteps')
    return [model, maxSteps === null ? null : text(`${maxSteps} 步上限`, `${maxSteps}-step limit`)].filter(Boolean).join(' · ') || null
  }
  if (event.type === 'SESSION_CONTEXT_IMPORTED') {
    const turns = payloadNumber(event, 'turnCount')
    return turns === null ? null : text(`承接 ${turns} 轮历史意图和摘要`, `Carried forward intent and summaries from ${turns} turns`)
  }
  if (event.type === 'MODEL_RETRY') {
    const next = payloadNumber(event, 'nextAttempt')
    const delay = payloadNumber(event, 'delayMillis')
    return text(`${delay === null ? '' : `${Math.ceil(delay / 1000)} 秒后`}进行第 ${next ?? '?'} 次尝试`,
      `Attempt ${next ?? '?'}${delay === null ? '' : ` in ${Math.ceil(delay / 1000)} seconds`}`)
  }
  if (event.type === 'AGENT_COMPLETED') return shortText(payloadString(event, 'summary'), 360)
  if (event.type === 'AGENT_PROGRESS') {
    const tool = payloadString(event, 'tool')
    if (tool) {
      const target = payloadString(event, 'path')
      const suffix = target ? ` ${target}` : ''
      if (tool === 'read_file') return text(`检查现有文件${suffix}`, `Inspecting the existing file${suffix}`)
      if (tool === 'write_file') return text(`应用文件修改${suffix}`, `Applying a file edit${suffix}`)
      if (tool === 'delete_file') return text(`删除文件${suffix}`, `Removing the file${suffix}`)
      if (tool === 'list_files') return text(`浏览工作区${suffix}`, `Mapping the workspace${suffix}`)
      if (tool === 'search_files') return text(`搜索相关代码${suffix}`, `Searching relevant code${suffix}`)
      if (tool === 'shell') return text('在隔离区运行项目命令', 'Running a project command in the isolated workspace')
      return text(`执行 ${toolLabel(tool)}`, `Running ${toolLabel(tool)}`)
    }
    return shortText(payloadString(event, 'summary'), 500)
  }
  if (event.type === 'RESULT_SNAPSHOT_SEALED') {
    const fingerprint = payloadString(event, 'fingerprint')
    return fingerprint ? text(`指纹 ${fingerprint.slice(0, 12)}`, `Fingerprint ${fingerprint.slice(0, 12)}`) : null
  }
  if (event.type === 'VERIFICATION_FINISHED') {
    const passed = payloadBoolean(event, 'passed')
    return passed === null ? null : passed ? text('全部验收命令通过', 'All acceptance commands passed') : text('有验收命令未通过', 'One or more acceptance commands failed')
  }
  if (event.type === 'VERIFICATION_INVALIDATED') {
    return text('项目验收命令已变化；保留的结果需要按新命令重新验收', 'Acceptance commands changed; the retained result must be verified again under the new policy')
  }
  if (event.type === 'VERIFICATION_INTERRUPTED') return shortText(payloadString(event, 'message') ?? payloadString(event, 'reason'), 300)
  if (event.type === 'EXPERIMENT_FAILED' || event.type === 'TASK_MEMORY_RECORD_FAILED' || event.type === 'TASK_MEMORY_UNAVAILABLE') {
    return shortText(payloadString(event, 'message'), 300)
  }
  if (event.type === 'PROMOTION_BLOCKED' || event.type === 'PROMOTION_RECOVERY_REQUIRED') {
    return shortText(payloadString(event, 'detail') ?? payloadString(event, 'status'), 300)
  }
  if (event.type === 'PROMOTED') {
    const changed = payloadArray(event, 'changedFiles').length
    return changed ? text(`${changed} 个文件已写入`, `${changed} files written`) : null
  }
  if (event.type === 'PROMOTION_FINISHED') {
    const status = payloadString(event, 'status')
    const detail = shortText(payloadString(event, 'detail'), 300)
    const fingerprint = payloadString(event, 'fingerprint')
    const suffix = fingerprint ? text(`指纹 ${fingerprint.slice(0, 12)}`, `Fingerprint ${fingerprint.slice(0, 12)}`) : null
    const statusLabel = lifecycleStatusLabel(status)
    return [statusLabel && text(`状态 ${statusLabel}`, `Status ${statusLabel}`), suffix, detail].filter(Boolean).join(' · ') || null
  }
  if (event.type === 'EXPERIMENT_RECOVERED') {
    const status = payloadString(event, 'status')
    const reason = shortText(payloadString(event, 'reason'), 300)
    return [status === 'FAILED' ? text('状态已收口为失败', 'Closed as failed') : status === 'AGENT_COMPLETED' ? text('结果保留，可重新验收', 'Result retained for re-verification') : lifecycleStatusLabel(status),
      reason].filter(Boolean).join(' · ') || null
  }
  return null
}

function entryKind(event: RunEvent): EntryKind {
  const category = runEventDefinition(event.type).category
  if (category === 'agent') return ['MODEL_REQUEST', 'MODEL_RESPONSE', 'AGENT_PROGRESS', 'MODEL_RETRY'].includes(event.type) ? 'thinking' : event.type.startsWith('TOOL_') ? 'tool' : 'context'
  if (category === 'verification') return 'verification'
  if (category === 'promotion') return 'promotion'
  if (category === 'memory') return 'memory'
  return 'lifecycle'
}

function entryTone(event: RunEvent): EntryTone {
  if (['EXPERIMENT_FAILED', 'PROMOTION_BLOCKED', 'TASK_MEMORY_RECORD_FAILED'].includes(event.type)) return 'danger'
  if (event.type === 'PROMOTION_FINISHED') {
    if (payloadBoolean(event, 'promoted') === true) return 'success'
    const status = payloadString(event, 'status') ?? ''
    return ['RECOVERY_REQUIRED', 'MANUAL_RECOVERY_REQUIRED', 'PROMOTION_LOCK_LOST'].some((value) => status.includes(value)) ? 'warning' : 'danger'
  }
  if (event.type === 'EXPERIMENT_RECOVERED') {
    const status = payloadString(event, 'status')
    return status === 'FAILED' ? 'danger' : status === 'AGENT_COMPLETED' ? 'warning' : 'neutral'
  }
  if (['MODEL_RETRY', 'VERIFICATION_WAITING', 'VERIFICATION_INTERRUPTED', 'VERIFICATION_INVALIDATED', 'PROMOTION_RECOVERY_REQUIRED', 'PROMOTION_RECOVERY_DEFERRED', 'TASK_MEMORY_UNAVAILABLE'].includes(event.type)) return 'warning'
  if (['VERIFICATION_FINISHED', 'RESULT_SNAPSHOT_SEALED', 'PROMOTED', 'PROMOTION_RECOVERED', 'PROMOTION_MANUALLY_RECONCILED'].includes(event.type)) {
    if (event.type === 'VERIFICATION_FINISHED' && payloadBoolean(event, 'passed') === false) return 'danger'
    return 'success'
  }
  if (['MODEL_REQUEST', 'TOOL_CALL', 'VERIFICATION_STARTED', 'PROMOTION_VERIFICATION_STARTED', 'PROMOTION_PREPARING'].includes(event.type)) return 'active'
  return 'neutral'
}

const entries = computed(() => {
  const result: TimelineEntry[] = []
  const byKey = new Map<string, TimelineEntry>()
  const modelByStep = new Map<number, TimelineEntry>()
  const modelByRequestSequence = new Map<number, TimelineEntry>()
  const sorted = [...props.events].sort((left, right) => left.sequence - right.sequence)

  function append(entry: TimelineEntry) {
    result.push(entry)
    byKey.set(entry.key, entry)
    if (entry.kind === 'thinking' && entry.modelState) {
      if (entry.modelStep !== null && entry.modelStep !== undefined) {
        modelByStep.set(entry.modelStep, entry)
      }
      if (entry.modelRequestSequence !== undefined) {
        modelByRequestSequence.set(entry.modelRequestSequence, entry)
      }
    }
    return entry
  }

  function bindModel(entry: TimelineEntry, step: number | null, requestSequence?: number) {
    // A legacy request may be paired with a newer response that does carry a
    // step. Keep the original request identity while adding the step alias so
    // subsequent tool/progress events resolve to the same row.
    if (step !== null) {
      entry.modelStep = step
      modelByStep.set(step, entry)
    } else if (entry.modelStep === undefined) {
      entry.modelStep = null
    }
    if (requestSequence !== undefined) {
      entry.modelRequestSequence = requestSequence
      modelByRequestSequence.set(requestSequence, entry)
    }
  }

  function latestOpenModel(legacyOnly = false) {
    return [...result].reverse().find((candidate) => candidate.kind === 'thinking'
      && candidate.modelState === 'requesting'
      && (!legacyOnly || candidate.modelStep === null)) ?? null
  }

  function findModel(step: number | null, sequence: number, includeCompleted = true) {
    if (step !== null) {
      const exact = modelByStep.get(step)
      if (exact?.modelState === 'requesting') return exact
      // Older streams could emit a public progress/tool event before the
      // response row. If that inferred completion is later followed by the
      // response, pair it with the same turn instead of creating a duplicate.
      if (exact && exact.modelState === 'responded' && exact.modelResponseSequence === undefined) return exact
      // Mixed-version timelines can contain a legacy request followed by a
      // response carrying a step. Pair only an unlabelled request, never an
      // unrelated labelled step.
      const legacy = latestOpenModel(true)
      if (legacy) return legacy
      return exact ?? null
    }
    const open = latestOpenModel()
    if (open) return open
    const inferred = [...result].reverse().find((candidate) => candidate.kind === 'thinking'
      && candidate.modelState === 'responded'
      && candidate.modelResponseSequence === undefined
      && (candidate.modelRequestSequence ?? candidate.sequence) <= sequence)
    if (inferred) return inferred
    if (!includeCompleted) return null
    // For progress/tool events without a step, prefer the latest model row
    // already seen. This prevents a second synthetic row for one legacy turn.
    return [...result].reverse().find((candidate) => candidate.kind === 'thinking'
      && candidate.modelState
      && (candidate.modelRequestSequence ?? candidate.sequence) <= sequence) ?? null
  }

  function markModelResponded(step: number | null, sequence: number) {
    const entry = findModel(step, sequence)
    if (!entry || entry.modelState !== 'requesting') return entry
    entry.modelState = 'responded'
    entry.pending = false
    entry.tone = 'neutral'
    entry.meta = text('已收到操作计划', 'Plan received')
    entry.title = text(`第 ${step ?? '?'} 步：已收到操作计划`, `Step ${step ?? '?'}: operation plan received`)
    return entry
  }

  function findTool(step: number | null, id: string) {
    const exact = byKey.get(toolEntryKey(step, id))
    // A legacy stream has no step to disambiguate a provider ID reused on a
    // later model turn. Reuse only an open row there; a completed row is a
    // separate historical call and must not be rewritten.
    if (exact && (step !== null || exact.toolState === 'queued' || exact.toolState === 'running')) return exact
    // A provider may omit `step` in one event family while including it in
    // another. Match the same call ID as a compatibility fallback, preferring
    // the most recent compatible row and never a completed row from another
    // labelled step when an exact identity exists.
    return [...result].reverse().find((candidate) => candidate.kind === 'tool'
      && candidate.toolId === id
      && (step === null
        ? (candidate.toolState === 'queued' || candidate.toolState === 'running')
        : candidate.toolStep === step
          || (candidate.toolStep === null && (candidate.toolState === 'queued' || candidate.toolState === 'running')))) ?? null
  }

  for (const event of sorted) {
    const step = stepOf(event)
    if (event.type === 'MODEL_REQUEST') {
      append({
        key: modelEntryKey(step, event.sequence),
        sequence: event.sequence,
        timestamp: event.timestamp,
        kind: 'thinking',
        tone: 'active',
        title: text(`第 ${step ?? '?'} 步：等待模型响应`, `Step ${step ?? '?'}: waiting for model response`),
        detail: null,
        meta: text('请求中', 'Requesting'),
        quiet: false,
        pending: props.running,
        modelState: 'requesting',
        modelStep: step,
        modelRequestSequence: event.sequence,
      })
      continue
    }
    if (event.type === 'MODEL_RESPONSE') {
      // Without a step, only an open request can prove that this response is
      // the completion of an existing row. If the retained tail starts at a
      // response, keep each standalone response as its own turn rather than
      // collapsing multiple responses onto the latest completed row.
      const matched = findModel(step, event.sequence, false)
      const key = matched?.key ?? modelEntryKey(step, event.sequence)
      const count = payloadNumber(event, 'toolCallCount') ?? payloadArray(event, 'toolCalls').length
      const entry = matched ?? append({
        key,
        sequence: event.sequence,
        timestamp: event.timestamp,
        kind: 'thinking',
        tone: 'neutral',
        title: '',
        detail: null,
        meta: null,
        quiet: false,
        pending: false,
        modelState: 'responded',
        modelStep: step,
      })
      bindModel(entry, step)
      entry.title = count > 0
        ? text(`第 ${step ?? entry.modelStep ?? '?'} 步：计划执行 ${count} 项操作`, `Step ${step ?? entry.modelStep ?? '?'}: ${count} operations planned`)
        : text(`第 ${step ?? entry.modelStep ?? '?'} 步：已整理答复`, `Step ${step ?? entry.modelStep ?? '?'}: response prepared`)
      entry.detail = shortText(payloadString(event, 'text'), 360)
      entry.tone = 'neutral'
      entry.pending = false
      entry.modelState = 'responded'
      entry.modelResponseSequence = event.sequence
      entry.meta = text('已完成', 'Completed')
      for (const rawCall of payloadArray(event, 'toolCalls')) {
        const parsed = parseModelToolCall(rawCall, event.sequence)
        if (!parsed || findTool(step, parsed.id)) continue
        const normalized = { ...parsed }
        append({
          key: toolEntryKey(step, parsed.id),
          sequence: event.sequence,
          timestamp: event.timestamp,
          kind: 'tool',
          tone: 'active',
          title: toolTitle(normalized),
          detail: toolDetail(normalized),
          meta: text('等待执行', 'Queued'),
          quiet: false,
          pending: props.running,
          toolState: 'queued',
          toolId: parsed.id,
          toolStep: step,
        })
      }
      continue
    }
    if (event.type === 'AGENT_PROGRESS') {
      markModelResponded(step, event.sequence)
      const toolName = payloadString(event, 'tool')
      if (toolName) {
        append({
          key: `progress-${event.eventId || event.sequence}`,
          sequence: event.sequence,
          timestamp: event.timestamp,
          kind: 'thinking',
          tone: 'active',
          title: text(`第 ${step ?? '?'} 步：行动说明`, `Step ${step ?? '?'}: progress update`),
          detail: eventDetail(event),
          // Keep provider/tool protocol names out of the compact activity row;
          // the full payload remains available in the review audit panel.
          meta: toolLabel(toolName),
          quiet: false,
          pending: false,
          modelState: undefined,
        })
      } else {
        const matched = findModel(step, event.sequence)
        const entry = matched ?? append({
          key: modelEntryKey(step, event.sequence),
          sequence: event.sequence,
          timestamp: event.timestamp,
          kind: 'thinking',
          tone: 'neutral',
          title: text(`第 ${step ?? '?'} 步：行动说明`, `Step ${step ?? '?'}: progress update`),
          detail: null,
          meta: null,
          quiet: false,
          pending: false,
          modelStep: step,
        })
        bindModel(entry, step)
        entry.detail = shortText(payloadString(event, 'summary'), 500)
      }
      continue
    }
    if (event.type === 'TOOL_CALL') {
      markModelResponded(step, event.sequence)
      const tool = toolPayload(event)
      const matched = findTool(step, tool.id)
      const entry = matched ?? append({
        key: toolEntryKey(step, tool.id),
        sequence: event.sequence,
        timestamp: event.timestamp,
        kind: 'tool',
        tone: 'active',
        title: '',
        detail: null,
        meta: null,
        quiet: false,
        pending: props.running,
        toolId: tool.id,
        toolStep: step,
      })
      entry.toolId = tool.id
      if (entry.toolStep === undefined || entry.toolStep === null) entry.toolStep = step
      entry.title = toolTitle(tool)
      entry.detail = toolDetail(tool)
      entry.meta = text('执行中', 'Running')
      entry.tone = 'active'
      entry.pending = props.running
      entry.toolState = 'running'
      continue
    }
    if (event.type === 'TOOL_RESULT') {
      markModelResponded(step, event.sequence)
      const tool = toolPayload(event)
      const matched = findTool(step, tool.id)
      const success = payloadBoolean(event, 'success') !== false
      const interrupted = payloadBoolean(event, 'interrupted') === true
      const entry = matched ?? append({
        key: toolEntryKey(step, tool.id),
        sequence: event.sequence,
        timestamp: event.timestamp,
        kind: 'tool',
        tone: interrupted ? 'warning' : success ? 'success' : 'danger',
        title: toolTitle(tool),
        detail: null,
        meta: null,
        quiet: false,
        pending: false,
        toolId: tool.id,
        toolStep: step,
      })
      entry.toolId = tool.id
      if (entry.toolStep === undefined || entry.toolStep === null) entry.toolStep = step
      const outputChars = payloadNumber(event, 'outputChars')
      entry.tone = interrupted ? 'warning' : success ? 'success' : 'danger'
      entry.pending = false
      entry.meta = interrupted ? text('已中断', 'Interrupted') : success ? text('已完成', 'Completed') : text('失败', 'Failed')
      entry.toolState = interrupted ? 'interrupted' : success ? 'completed' : 'failed'
      if (!entry.detail && outputChars !== null) {
        const truncated = payloadBoolean(event, 'outputTruncated') === true
        entry.detail = success
          ? truncated
            ? text(`返回 ${outputChars} 个字符；活动记录按安全上限截断`, `${outputChars} characters returned; Activity keeps a safety-limited excerpt`)
            : text(`返回 ${outputChars} 个字符，输出已保留在活动记录中`, `${outputChars} characters returned; output retained in Activity`)
          : truncated
            ? text(`错误输出 ${outputChars} 个字符；活动记录按安全上限截断`, `${outputChars} error characters; Activity keeps a safety-limited excerpt`)
            : text(`错误输出 ${outputChars} 个字符，详情已保留在活动记录中`, `${outputChars} error characters; details retained in Activity`)
      }
      continue
    }

    if (event.type === 'AGENT_COMPLETED') markModelResponded(step, event.sequence)
    append({
      key: `event-${event.eventId || event.sequence}`,
      sequence: event.sequence,
      timestamp: event.timestamp,
      kind: entryKind(event),
      tone: entryTone(event),
      title: runEventLabel(event, text),
      detail: eventDetail(event),
      meta: null,
      quiet: runEventDefinition(event.type).importance === 'diagnostic',
      pending: false,
    })
  }
  // A worker can disappear after TOOL_CALL and before TOOL_RESULT (for
  // example, a process crash). Keep the activity row honest once the
  // lifecycle has stopped instead of leaving it looking permanently active.
  if (!props.running) {
    for (const entry of result) {
      if (entry.kind !== 'thinking' || entry.modelState !== 'requesting') continue
      entry.modelState = 'interrupted'
      entry.pending = false
      entry.tone = 'warning'
      entry.meta = text('未收到模型响应', 'No model response')
      if (!entry.detail) {
        entry.detail = text('运行已结束，未收到这一步的模型响应', 'The run ended before this model step produced a response')
      }
    }
    for (const entry of result) {
      if (entry.kind !== 'tool' || (entry.toolState !== 'queued' && entry.toolState !== 'running')) continue
      const priorState = entry.toolState
      entry.toolState = 'interrupted'
      entry.pending = false
      entry.tone = 'warning'
      entry.meta = priorState === 'running'
        ? text('已中断', 'Interrupted')
        : text('未执行', 'Not executed')
      if (!entry.detail) {
        entry.detail = text('运行已结束，未收到工具完成事件', 'The run ended before a tool completion event arrived')
      }
    }
  }
  return result
})

const collapsedEntries = computed(() => {
  const highSignal = entries.value.filter((entry) => !entry.quiet)
  // A short history can consist entirely of diagnostic context events. Keep
  // a compact tail visible in that case instead of rendering an empty list
  // that looks like the stream has not started.
  return highSignal.length ? highSignal : entries.value
})

const visibleEntries = computed(() => {
  if (expanded.value) return entries.value
  return collapsedEntries.value.slice(-COLLAPSED_ENTRIES)
})

// Count the entries that the toggle actually reveals. This intentionally
// includes quiet diagnostic rows omitted from the collapsed high-signal view.
const hiddenEntryCount = computed(() => expanded.value
  ? 0
  : Math.max(0, entries.value.length - visibleEntries.value.length))
const latestEvent = computed(() => props.events.at(-1) ?? null)

watch(() => latestEvent.value?.eventId, () => {
  const event = latestEvent.value
  if (!event || !props.running) return
  const eventTime = Date.parse(event.timestamp)
  if (!Number.isFinite(eventTime) || Math.abs(Date.now() - eventTime) > 15_000) return
  announcement.value = runEventLabel(event, text)
})

function clock(value: string) {
  const locale = typeof document === 'undefined' ? undefined : document.documentElement.lang
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return text('时间未知', 'unknown time')
  try {
    return new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(date)
  } catch {
    return text('时间未知', 'unknown time')
  }
}

function timestampTitle(value: string) {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return text('时间未知', 'Unknown time')
  try {
    return date.toLocaleString()
  } catch {
    return text('时间未知', 'Unknown time')
  }
}

function entryMeta(entry: TimelineEntry) {
  if (!entry.meta || !entry.pending || !props.running) return entry.meta
  const started = Date.parse(entry.timestamp)
  if (!Number.isFinite(started)) return entry.meta
  const seconds = Math.max(0, Math.floor((now.value - started) / 1_000))
  return `${entry.meta} · ${text(`${seconds} 秒`, `${seconds}s`)}`
}

function streamLabel() {
  if (props.streamState === 'live') return text('实时', 'live')
  if (props.streamState === 'reconnecting') return text('重连中', 'reconnecting')
  if (props.streamState === 'offline') return text('活动可能延迟', 'updates may be delayed')
  if (props.streamState === 'connecting') return text('连接中', 'connecting')
  // A completed run has no live connection by design, while a running run can
  // briefly be idle before EventSource opens. Keep those two cases distinct so
  // the activity header does not imply that a live stream is already settled.
  return props.running ? text('等待连接', 'waiting for connection') : text('已保留', 'retained')
}
</script>

<template>
  <section v-if="events.length || running" class="run-activity" :class="{ expanded }" :aria-label="text('Agent 运行活动', 'Agent run activity')" @click.stop>
    <div class="sr-only" role="status" aria-live="polite" aria-atomic="true">{{ announcement }}</div>
    <header class="run-activity-header">
      <div class="run-activity-title">
        <Database :size="14" aria-hidden="true" />
        <strong>{{ text('运行活动', 'Run activity') }}</strong>
        <span>{{ truncated ? text(`显示最近 ${entries.length} 个活动步骤；更早事件可能已按保留策略清理`, `Showing the latest ${entries.length} activity steps; older events may follow the retention policy`) : text(`显示 ${entries.length} 个活动步骤${events.length !== entries.length ? ` · ${events.length} 条原始事件` : ''}`, `${entries.length} activity steps${events.length !== entries.length ? ` · ${events.length} raw events` : ''}`) }}</span>
      </div>
      <span class="run-activity-connection" :class="streamState"><span class="stream-dot" :class="streamState" />{{ streamLabel() }}</span>
    </header>

    <ol v-if="visibleEntries.length" :id="activityListId" class="run-activity-list">
      <li v-for="entry in visibleEntries" :key="entry.key" :class="[`kind-${entry.kind}`, `tone-${entry.tone}`]">
        <span class="run-activity-icon" aria-hidden="true">
          <LoaderCircle v-if="entry.pending" class="spin" :size="14" />
          <BrainCircuit v-else-if="entry.kind === 'thinking'" :size="14" />
          <TerminalSquare v-else-if="entry.kind === 'tool' && entry.title.includes(text('命令', 'command'))" :size="14" />
          <FileText v-else-if="entry.kind === 'tool' && entry.title.includes(text('读取', 'Read'))" :size="14" />
          <FilePenLine v-else-if="entry.kind === 'tool' && entry.title.includes(text('写入', 'Write'))" :size="14" />
          <Trash2 v-else-if="entry.kind === 'tool' && entry.title.includes(text('删除', 'Delete'))" :size="14" />
          <FileSearch v-else-if="entry.kind === 'tool' && entry.title.includes(text('搜索', 'Search'))" :size="14" />
          <ListTree v-else-if="entry.kind === 'tool' && entry.title.includes(text('浏览', 'List'))" :size="14" />
          <Wrench v-else-if="entry.kind === 'tool'" :size="14" />
          <ShieldCheck v-else-if="entry.kind === 'verification'" :size="14" />
          <GitCommitHorizontal v-else-if="entry.kind === 'promotion'" :size="14" />
          <MemoryStick v-else-if="entry.kind === 'memory'" :size="14" />
          <Database v-else-if="entry.kind === 'context'" :size="14" />
          <X v-else-if="entry.tone === 'danger'" :size="14" />
          <AlertTriangle v-else-if="entry.tone === 'warning'" :size="14" />
          <Check v-else-if="entry.tone === 'success'" :size="14" />
          <CircleDot v-else :size="14" />
        </span>
        <div class="run-activity-copy">
           <div><strong>{{ entry.title }}</strong><span v-if="entry.meta">{{ entryMeta(entry) }}</span></div>
          <p v-if="entry.detail">{{ entry.detail }}</p>
        </div>
        <time :datetime="entry.timestamp" :title="timestampTitle(entry.timestamp)">{{ clock(entry.timestamp) }}</time>
      </li>
    </ol>
    <div v-else class="run-activity-empty" role="status">
      <LoaderCircle class="spin" :size="14" />
      <span>{{ text('正在等待第一条活动记录…', 'Waiting for the first activity event…') }}</span>
    </div>

    <button
      v-if="hiddenEntryCount > 0 || expanded"
      type="button"
      class="run-activity-toggle"
      :aria-expanded="expanded ? 'true' : 'false'"
      :aria-controls="activityListId"
      @click.stop="expanded = !expanded"
    >
      <ChevronUp v-if="expanded" :size="15" />
      <ChevronDown v-else :size="15" />
      {{ expanded ? text('收起活动', 'Collapse activity') : text(`查看全部活动${hiddenEntryCount ? `（另有 ${hiddenEntryCount} 个步骤）` : ''}`, `Show all activity${hiddenEntryCount ? ` (${hiddenEntryCount} more steps)` : ''}`) }}
    </button>
  </section>
</template>
