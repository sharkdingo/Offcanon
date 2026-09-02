<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { AlertTriangle, CircleDot, Edit3, RefreshCw, Settings, ShieldCheck, X } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, type Project, type RunEvent } from '../api'
import AgentThread from '../components/AgentThread.vue'
import ProjectDialog from '../components/ProjectDialog.vue'
import PromotionDialog from '../components/PromotionDialog.vue'
import TaskSidebar from '../components/TaskSidebar.vue'
import ExperimentReview from '../components/ExperimentReview.vue'
import { useLocale } from '../i18n'
import { RUN_EVENTS_REQUIRING_REFRESH } from '../runEvents'
import { useWorkspaceStore } from '../stores/workspace'
import { experimentBlocksSession, formatError } from '../ui'

type StreamState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline'

const route = useRoute()
const router = useRouter()
const { auth, text } = useLocale()
const store = useWorkspaceStore()
const threadRef = ref<InstanceType<typeof AgentThread> | null>(null)
const showProjectDialog = ref(false)
const editingProject = ref<Project | null>(null)
const showReview = ref(false)
const showPromotionDialog = ref(false)
const mobileNavOpen = ref(false)
const isMobile = ref(false)
const reviewDrawer = ref<HTMLElement | null>(null)
const reviewClose = ref<HTMLElement | null>(null)
const reviewReturnFocus = ref<HTMLElement | null>(null)
const workspaceContent = ref<HTMLElement | null>(null)
const submitting = ref(false)
const actionBusy = ref(false)
const agentModelReady = ref(false)
const forceNewTask = ref(false)
const activityByExperiment = ref<Record<string, RunEvent[]>>({})
const streamStateByExperiment = ref<Record<string, StreamState>>({})
const eventWarningByExperiment = ref<Record<string, string | null>>({})
const activityTruncatedByExperiment = ref<Record<string, boolean>>({})
const initialized = ref(false)
const eventSources = new Map<string, EventSource>()
const seenSequences = new Map<string, Set<number>>()
const refreshTimers = new Map<string, number>()
let reconciliationTimer: number | null = null
let reconciliationBusy = false
const pendingReconciliation = new Set<string>()
let routeSyncVersion = 0
let selectionIntent = 0
const ACTIVITY_WINDOW = 200
const RECONCILIATION_INTERVAL_MS = 3_000
const RECONCILIATION_STATUSES = new Set([
  'CREATED',
  'SNAPSHOTTING',
  'RUNNING',
  'VERIFYING',
  'PREPARING_PROMOTION',
  'PROMOTING',
])

function projectSaveError(cause: unknown) {
  if (cause instanceof ApiError && cause.code === 'PROJECT_ALREADY_REGISTERED') {
    return text('这个 Git 仓库已由其他账户打开。请切换到账户，或选择另一个仓库。', 'This Git repository is already registered by another account. Switch accounts or choose another repository.')
  }
  if (cause instanceof ApiError && cause.code === 'VERIFICATION_POLICY_MISSING') {
    return text('请先在项目设置中配置至少一条验收命令。', 'Configure at least one acceptance command in project settings first.')
  }
  if (cause instanceof ApiError && cause.code === 'PROJECT_PARENT_NOT_FOUND') {
    return text('新项目的父目录必须已经存在。', 'The parent directory for a new project must already exist.')
  }
  if (cause instanceof ApiError && cause.code === 'PROJECT_TARGET_NOT_EMPTY') {
    return text('新项目目录必须为空，请选择一个新目录。', 'The new project directory must be empty. Choose a new directory.')
  }
  if (cause instanceof ApiError && cause.code === 'PROJECT_GIT_INIT_FAILED') {
    return text('无法初始化 Git，请确认本机已安装 Git 且目录可写。', 'Git could not be initialized. Check that Git is installed and the directory is writable.')
  }
  if (cause instanceof ApiError && cause.code === 'PROJECT_PARENT_NOT_WRITABLE') {
    return text('没有权限在此父目录创建项目。', 'You do not have permission to create a project in this parent directory.')
  }
  return formatError(cause, '无法保存项目。', 'Unable to save project')
}

const projectParam = computed(() => typeof route.params.projectId === 'string' ? route.params.projectId : null)
const experimentParam = computed(() => typeof route.params.experimentId === 'string' ? route.params.experimentId : null)
const accountInitials = computed(() => auth.session?.displayName
  .split(/\s+/)
  .map((part) => part[0])
  .join('')
  .slice(0, 2)
  .toUpperCase() ?? 'O')

const activeExperiments = computed(() => store.activeExperiments)
const latestExperiment = computed(() => activeExperiments.value.at(-1) ?? null)
const verifiedResultCount = computed(() => store.experiments.filter((experiment) => experiment.status === 'VERIFIED').length)
const mainActivityExperimentId = computed(() => latestExperiment.value?.id ?? (!showReview.value ? store.selectedExperimentId : null))
const reviewActivityExperimentId = computed(() => showReview.value ? store.selectedExperimentId : null)

function activityFor(experimentId: string | null) {
  return experimentId ? activityByExperiment.value[experimentId] ?? [] : []
}

function streamStateFor(experimentId: string | null): StreamState {
  return experimentId ? streamStateByExperiment.value[experimentId] ?? 'idle' : 'idle'
}

function eventWarningFor(experimentId: string | null) {
  return experimentId ? eventWarningByExperiment.value[experimentId] ?? null : null
}

function activityTruncatedFor(experimentId: string | null) {
  return experimentId ? activityTruncatedByExperiment.value[experimentId] ?? false : false
}

const mainActivity = computed(() => activityFor(mainActivityExperimentId.value))
const mainStreamState = computed(() => streamStateFor(mainActivityExperimentId.value))
const reviewActivity = computed(() => activityFor(reviewActivityExperimentId.value))
const reviewStreamState = computed(() => streamStateFor(reviewActivityExperimentId.value))
const reviewEventWarning = computed(() => eventWarningFor(reviewActivityExperimentId.value))
const selectedSessionBusy = computed(() => {
  const selected = store.selectedExperiment
  if (!selected) return false
  return store.experiments.some((candidate) => candidate.id !== selected.id
    && candidate.sessionId === selected.sessionId
    && experimentBlocksSession(candidate))
})

const connectionLabel = computed(() => {
  const labels: Record<StreamState, [string, string]> = {
    idle: ['空闲', 'idle'], connecting: ['连接中', 'connecting'], live: ['实时', 'live'],
    reconnecting: ['重连中', 'reconnecting'], offline: ['离线', 'offline'],
  }
  const pair = labels[mainStreamState.value]
  return text(pair[0], pair[1])
})

function validRunEvent(value: unknown): value is RunEvent {
  if (!value || typeof value !== 'object') return false
  const event = value as Partial<RunEvent>
  return typeof event.eventId === 'string'
    && typeof event.experimentId === 'string'
    && typeof event.sequence === 'number' && Number.isInteger(event.sequence) && event.sequence >= 1
    && typeof event.type === 'string' && typeof event.timestamp === 'string'
    && !!event.payload && typeof event.payload === 'object'
}

function scheduleProjectRefresh(experimentId: string, attempt = 0) {
  const previous = refreshTimers.get(experimentId)
  if (previous !== undefined) window.clearTimeout(previous)
  const timer = window.setTimeout(async () => {
    refreshTimers.delete(experimentId)
    const source = store.experiments.find((experiment) => experiment.id === experimentId)
    if (!source || store.selectedProjectId !== source.projectId) {
      pendingReconciliation.delete(experimentId)
      return
    }
    // A lighter reconciliation may have already converged this event. Do not
    // let an older backoff callback report a later, spurious failure.
    if (!pendingReconciliation.has(experimentId)) return
    try {
      await store.reloadSelectedProject()
      // The project may have changed while the full refresh was in flight.
      // Never clear a pending refresh for a different project.
      if (store.selectedProjectId !== source.projectId
        || !store.experiments.some((experiment) => experiment.id === experimentId)) return
      pendingReconciliation.delete(experimentId)
      clearStateRefreshError()
    } catch (cause) {
      if (store.selectedProjectId !== source.projectId
        || !store.experiments.some((experiment) => experiment.id === experimentId)) {
        pendingReconciliation.delete(experimentId)
        return
      }
      if (attempt >= 4) store.error = stateRefreshError()
      // Keep retrying with a capped backoff. The pending set is intentionally
      // retained until the full project refresh succeeds, because a row-level
      // GET cannot hydrate sessions, evidence, diffs, or promotion recovery.
      scheduleProjectRefresh(experimentId, attempt + 1)
    }
  }, attempt === 0 ? 300 : Math.min(30_000, 500 * 2 ** Math.min(attempt - 1, 6)))
  refreshTimers.set(experimentId, timer)
}

function updateStreamState(experimentId: string, state: StreamState) {
  streamStateByExperiment.value = { ...streamStateByExperiment.value, [experimentId]: state }
}

function updateEventWarning(experimentId: string, warning: string | null) {
  eventWarningByExperiment.value = { ...eventWarningByExperiment.value, [experimentId]: warning }
}

function reconciliationIds() {
  const active = store.experiments
    .filter((experiment) => RECONCILIATION_STATUSES.has(experiment.status)
      || (experiment.status === 'AGENT_COMPLETED' && !experiment.resultSnapshotId))
    .map((experiment) => experiment.id)
  const known = new Set(store.experiments.map((experiment) => experiment.id))
  for (const experimentId of pendingReconciliation) {
    if (!known.has(experimentId)) pendingReconciliation.delete(experimentId)
  }
  return [...new Set([...active, ...pendingReconciliation])]
}

function stateSyncWarning() {
  return text('任务状态同步暂时失败，将继续重试。', 'Task state sync failed temporarily; it will keep retrying.')
}

function stateRefreshError() {
  return text('无法刷新任务状态，将继续保留当前活动并等待重试。', 'Unable to refresh task state; the current activity is retained and will be retried.')
}

function clearStateRefreshError() {
  if (store.error === stateRefreshError()) store.error = null
}

function isStateSyncWarning(value: string | null) {
  return value === stateSyncWarning()
}

function clearStateSyncWarnings() {
  for (const experimentId of Object.keys(eventWarningByExperiment.value)) {
    if (isStateSyncWarning(eventWarningByExperiment.value[experimentId])) updateEventWarning(experimentId, null)
  }
}

function stopStateReconciliation() {
  if (reconciliationTimer !== null) window.clearInterval(reconciliationTimer)
  reconciliationTimer = null
}

async function reconcileActiveState() {
  if (reconciliationBusy) return
  const ids = reconciliationIds()
  if (!ids.length) {
    clearStateSyncWarnings()
    clearStateRefreshError()
    stopStateReconciliation()
    return
  }
  reconciliationBusy = true
  try {
    const results = await Promise.allSettled(ids.map((experimentId) => store.refreshExperimentState(experimentId)))
    results.forEach((result) => {
      if (result.status === 'fulfilled' && result.value === true) clearStateRefreshError()
    })
    // A full refresh can be delayed or lost independently of the lightweight
    // row request. Make sure each pending ID still has a full-refresh retry.
    for (const experimentId of ids) {
      if (pendingReconciliation.has(experimentId) && !refreshTimers.has(experimentId)) {
        scheduleProjectRefresh(experimentId)
      }
    }
    if (results.some((result) => result.status === 'rejected')) {
      const warningId = mainActivityExperimentId.value ?? ids[0]
      updateEventWarning(warningId, stateSyncWarning())
    } else {
      for (const experimentId of ids) {
        if (isStateSyncWarning(eventWarningFor(experimentId))) updateEventWarning(experimentId, null)
      }
    }
  } finally {
    reconciliationBusy = false
    syncStateReconciliation()
  }
}

function syncStateReconciliation() {
  if (!reconciliationIds().length) {
    clearStateSyncWarnings()
    clearStateRefreshError()
    stopStateReconciliation()
    return
  }
  if (reconciliationTimer === null) {
    reconciliationTimer = window.setInterval(() => void reconcileActiveState(), RECONCILIATION_INTERVAL_MS)
  }
}

function connectEvents(experimentId: string | null, force = false) {
  if (!experimentId) return
  const existing = eventSources.get(experimentId)
  if (!force && existing && existing.readyState !== EventSource.CLOSED) return
  existing?.close()
  eventSources.delete(experimentId)

  const retained = activityFor(experimentId)
  const cursor = retained.at(-1)?.sequence ?? 0
  const query = cursor > 0 ? `after=${cursor}` : `tail=${ACTIVITY_WINDOW}`
  updateEventWarning(experimentId, null)
  updateStreamState(experimentId, 'connecting')

  // EventSource does not expose arbitrary headers. Explicit credentials keep
  // the HttpOnly session cookie attached when the frontend is served through a
  // development/reverse-proxy origin.
  const source = new EventSource(`/api/experiments/${experimentId}/events?${query}`, { withCredentials: true })
  eventSources.set(experimentId, source)
  source.onopen = () => {
    if (eventSources.get(experimentId) === source) updateStreamState(experimentId, 'live')
  }
  source.onerror = () => {
    if (eventSources.get(experimentId) !== source) return
    updateStreamState(experimentId, source.readyState === EventSource.CLOSED ? 'offline' : 'reconnecting')
  }
  source.onmessage = (message) => {
    if (eventSources.get(experimentId) !== source) return
    let parsed: unknown
    try { parsed = JSON.parse(message.data) } catch {
      updateEventWarning(experimentId, text('事件内容无法解析。', 'An event payload could not be parsed.'))
      return
    }
    if (!validRunEvent(parsed)) {
      updateEventWarning(experimentId, text('已忽略一个格式无效的事件。', 'An event with an invalid shape was ignored.'))
      return
    }
    if (parsed.experimentId !== experimentId) {
      updateEventWarning(experimentId, text('已忽略了属于其他任务的事件。', 'An event for another task was ignored.'))
      return
    }
    const seen = seenSequences.get(experimentId) ?? new Set<number>()
    seenSequences.set(experimentId, seen)
    if (seen.has(parsed.sequence)) return
    seen.add(parsed.sequence)
    const current = activityFor(experimentId)
    // A reconnect can resume after the server has evicted older events.  The
    // client may still have a non-empty tail in memory, so checking only the
    // first event is not enough to detect a gap in the retained timeline.
    const previousSequence = current.at(-1)?.sequence ?? 0
    if ((current.length === 0 && parsed.sequence > 1)
      || (previousSequence > 0 && parsed.sequence > previousSequence + 1)) {
      activityTruncatedByExperiment.value = { ...activityTruncatedByExperiment.value, [experimentId]: true }
    }
    let next = [...current, parsed]
    if (next.length > ACTIVITY_WINDOW) {
      next = next.slice(-ACTIVITY_WINDOW)
      activityTruncatedByExperiment.value = { ...activityTruncatedByExperiment.value, [experimentId]: true }
    }
    // Keep duplicate detection bounded with the visible retention window. The
    // SSE cursor still prevents replaying older events after reconnect; this
    // set only protects the current in-memory tail from duplicate deliveries.
    seen.clear()
    for (const event of next) seen.add(event.sequence)
    activityByExperiment.value = { ...activityByExperiment.value, [experimentId]: next }
    if (RUN_EVENTS_REQUIRING_REFRESH.has(parsed.type)) {
      pendingReconciliation.add(experimentId)
      scheduleProjectRefresh(experimentId)
      syncStateReconciliation()
    }
  }
}

function disconnectEventStream(experimentId: string) {
  eventSources.get(experimentId)?.close()
  eventSources.delete(experimentId)
  updateStreamState(experimentId, 'idle')
}

function syncEventStreams() {
  const desired = new Set<string>()
  if (mainActivityExperimentId.value) desired.add(mainActivityExperimentId.value)
  if (reviewActivityExperimentId.value) desired.add(reviewActivityExperimentId.value)
  for (const experimentId of desired) connectEvents(experimentId)
  for (const experimentId of [...eventSources.keys()]) {
    if (!desired.has(experimentId)) disconnectEventStream(experimentId)
  }
  syncStateReconciliation()
}

function disconnectAllEventStreams() {
  for (const source of eventSources.values()) source.close()
  eventSources.clear()
  for (const timer of refreshTimers.values()) window.clearTimeout(timer)
  refreshTimers.clear()
  stopStateReconciliation()
  pendingReconciliation.clear()
}

async function syncRoute() {
  if (!initialized.value) return
  // A URL change (including browser back/forward) supersedes any pending
  // click handler that is still waiting for a store request.
  selectionIntent++
  const syncId = ++routeSyncVersion
  const desiredProject = projectParam.value
  const desiredExperiment = experimentParam.value
  if (!desiredProject) {
    store.clearSelection()
    showReview.value = false
    mobileNavOpen.value = true
    forceNewTask.value = false
    syncEventStreams()
    return
  }
  if (store.projects.length === 0 && store.error) {
    syncEventStreams()
    return
  }
  if (!store.projects.some((project) => project.id === desiredProject)) {
    store.error = text('链接中的项目不可用，请从项目列表重新选择。', 'The linked project is unavailable. Choose it again from the project list.')
    syncEventStreams()
    await router.replace({ name: 'home' })
    return
  }
  if (store.selectedProjectId !== desiredProject) {
    await store.selectProject(desiredProject, desiredExperiment)
    if (syncId !== routeSyncVersion) return
    if (desiredExperiment && store.selectedExperimentId !== desiredExperiment) {
      store.error = text('链接的任务不可用。', 'The linked task is unavailable.')
      syncEventStreams()
      await router.replace({ name: 'project', params: { projectId: desiredProject } })
      return
    }
  } else if (desiredExperiment && store.selectedExperimentId !== desiredExperiment) {
    if (store.experiments.some((experiment) => experiment.id === desiredExperiment)) {
      await store.selectExperiment(desiredExperiment)
      if (syncId !== routeSyncVersion) return
    } else {
      store.error = text('链接的任务不可用。', 'The linked task is unavailable.')
      syncEventStreams()
      await router.replace({ name: 'project', params: { projectId: desiredProject } })
      return
    }
  }
  if (syncId !== routeSyncVersion) return
  showReview.value = !!desiredExperiment
  syncEventStreams()
}

async function openProject(projectId: string) {
  selectionIntent++
  closeMobileNav()
  forceNewTask.value = false
  await router.push({ name: 'project', params: { projectId } })
}

async function selectSession(sessionId: string) {
  const intent = ++selectionIntent
  closeMobileNav()
  forceNewTask.value = false
  await store.selectSession(sessionId)
  if (intent !== selectionIntent) return
  if (!store.selectedProjectId) return
  const latest = store.activeExperiments.at(-1)
  if (latest) await router.push({ name: 'project', params: { projectId: store.selectedProjectId } })
  else if (experimentParam.value) await router.push({ name: 'project', params: { projectId: store.selectedProjectId } })
}

async function openReview(experimentId: string) {
  const intent = ++selectionIntent
  closeMobileNav()
  reviewReturnFocus.value = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await store.selectExperiment(experimentId)
  if (intent !== selectionIntent) return
  showReview.value = true
  if (store.selectedProjectId) {
    await router.push({ name: 'experiment', params: { projectId: store.selectedProjectId, experimentId } })
  }
}

async function selectExperiment(experimentId: string) {
  const intent = ++selectionIntent
  await store.selectExperiment(experimentId)
  if (intent !== selectionIntent) return
  if (store.selectedProjectId) {
    // Keep browser history/deep links aligned with selections made in the
    // conversation list, including historical turns.
    await router.push({ name: 'experiment', params: { projectId: store.selectedProjectId, experimentId } })
  }
}

async function closeReview() {
  selectionIntent++
  if (showPromotionDialog.value && actionBusy.value) return
  const returnFocus = reviewReturnFocus.value
  showReview.value = false
  await nextTick()
  if (returnFocus && document.contains(returnFocus)) returnFocus.focus()
  else document.querySelector<HTMLElement>('[data-turn-select][aria-pressed="true"]')?.focus()
  reviewReturnFocus.value = null
  if (store.selectedProjectId) await router.push({ name: 'project', params: { projectId: store.selectedProjectId } })
}

async function continueFromReview() {
  await closeReview()
  await threadRef.value?.focusComposer()
}

async function submitProject(value: { name: string; canonicalPath: string; verificationCommands: string[]; createNew?: boolean }) {
  submitting.value = true
  store.error = null
  try {
    const editing = Boolean(editingProject.value)
    const { createNew, ...projectBody } = value
    const project = editingProject.value
      ? await store.updateProject(projectBody)
      : createNew
        ? await store.createLocalProject(projectBody)
        : await store.createProject(projectBody)
    if (!project) return
    const reopened = 'project' in project && project.reopened
    const selected = 'project' in project ? project.project : project
    editingProject.value = null
    showProjectDialog.value = false
    await router.push({ name: 'project', params: { projectId: selected.id } })
    if (editing && selected.id === store.selectedProjectId) {
      // Policy changes may return verified results to the sealed waiting state
      // on the server. Refresh the experiment list so the workspace reflects
      // that safety transition immediately.
      try {
        await store.reloadSelectedProject()
      } catch (cause) {
        store.error = formatError(cause, '项目已保存，但任务状态刷新失败。', 'The project was saved, but task status could not be refreshed.')
      }
    }
    if (reopened) {
      store.error = text('已打开这个账户中的现有项目；名称和验收命令沿用原设置，可通过顶部的「项目设置」修改。', 'Opened the existing project in this account. Its saved name and acceptance commands were kept; use Project settings in the header to change them.')
    }
  } catch (cause) {
    store.error = projectSaveError(cause)
  } finally { submitting.value = false }
}

function openProjectDialog() {
  store.error = null
  editingProject.value = null
  showProjectDialog.value = true
}

function editSelectedProject() {
  if (!store.selectedProject) return
  store.error = null
  editingProject.value = store.selectedProject
  showProjectDialog.value = true
}

function defaultSessionTitle(task: string) {
  const compact = task.replace(/\s+/g, ' ').trim()
  return compact.length > 52 ? `${compact.slice(0, 52)}...` : compact
}

async function submitTask(task: string, newTask = false) {
  if (!store.selectedProjectId || submitting.value || actionBusy.value) return
  submitting.value = true
  store.error = null
  try {
    let experiment
    const createFresh = newTask || forceNewTask.value
    if (!createFresh && latestExperiment.value && store.selectedSessionId) {
      experiment = await store.continueExperiment(latestExperiment.value.id, task)
    } else {
      experiment = await store.createExperiment({
        sessionTitle: defaultSessionTitle(task),
        task,
        newSession: true,
      })
      if (experiment && experiment.status === 'READY_TO_RUN') await store.startExperiment(experiment.id)
    }
    if (experiment) {
      forceNewTask.value = false
      showReview.value = false
      await router.push({ name: 'project', params: { projectId: experiment.projectId } })
    }
  } catch (cause) {
    store.error = formatError(cause, '无法开始任务。', 'Unable to start the task')
  } finally { submitting.value = false }
}

async function retryExperiment(experimentId: string) {
  if (submitting.value || actionBusy.value) return
  const source = store.experiments.find((item) => item.id === experimentId)
  if (!source || !store.selectedProjectId) return
  submitting.value = true
  store.error = null
  try {
    const successor = await store.continueExperiment(experimentId, source.task)
    if (successor?.status === 'READY_TO_RUN') await store.startExperiment(successor.id)
    if (successor) {
      showReview.value = false
      await router.push({ name: 'project', params: { projectId: successor.projectId } })
    }
  } catch (cause) {
    store.error = formatError(cause, '无法重试任务。', 'Unable to retry the task')
  } finally {
    submitting.value = false
  }
}

function openSettings() {
  selectionIntent++
  showReview.value = false
  showPromotionDialog.value = false
  const query: Record<string, string> = {}
  if (store.selectedProjectId) query.projectId = store.selectedProjectId
  if (store.selectedExperimentId) query.experimentId = store.selectedExperimentId
  void router.push({ name: 'settings', query })
}

function openProjectSettings() {
  selectionIntent++
  showReview.value = false
  showPromotionDialog.value = false
  // Editing from the review drawer leaves the experiment deep-link. Replace
  // it before closing the drawer so cancelling the settings dialog cannot
  // leave the URL pointing at a review that is no longer visible (and a later
  // refresh will not reopen that stale drawer).
  if (store.selectedProjectId && experimentParam.value) {
    void router.replace({ name: 'project', params: { projectId: store.selectedProjectId } })
  }
  editSelectedProject()
}

async function startNewTask() {
  selectionIntent++
  forceNewTask.value = true
  mobileNavOpen.value = false
  await store.selectSession(null)
  store.clearExperimentSelection()
  if (store.selectedProjectId) {
    await router.push({ name: 'project', params: { projectId: store.selectedProjectId } })
  }
  await threadRef.value?.focusComposer()
}

async function startExperiment(experimentId: string) {
  await runAction(() => store.startExperiment(experimentId))
}

async function cancelExperiment(experimentId: string) {
  await runAction(() => store.cancelExperiment(experimentId))
}

async function verifyExperiment(experimentId: string) {
  await runAction(() => store.verifyExperiment(experimentId))
}

async function reconcileSelected() {
  const experiment = store.selectedExperiment
  if (!experiment) return
  await runAction(() => store.reconcilePromotion(experiment.id))
}

async function reconcileProject() {
  await runAction(() => store.reconcileProjectRecovery())
}

async function runAction(action: () => Promise<void>, closePromotion = false) {
  if (actionBusy.value) return
  actionBusy.value = true
  store.error = null
  try {
    await action()
    if (closePromotion) showPromotionDialog.value = false
  } catch (cause) {
    store.error = formatError(cause, '任务操作失败。', 'Task action failed')
  } finally { actionBusy.value = false }
}

async function confirmPromotionDecision() {
  const experiment = store.selectedExperiment
  const preview = store.promotionPreview
  // The review action is intentionally fail-closed. A detail request can fail
  // after the confirmation dialog opens, and applying without a complete
  // review would break the user's evidence-first decision model.
  if (!experiment || experiment.status !== 'VERIFIED' || !preview
    || selectedSessionBusy.value || preview.recoveryRequired || (!preview.promotable && !preview.conflict)
    || store.evidenceError || store.diffError || store.promotionPreviewError || store.promotionRecoveryError) {
    showPromotionDialog.value = false
    return
  }
  await runAction(() => preview.conflict
    ? store.confirmExperimentStale(experiment.id)
    : store.promoteExperiment(experiment.id), true)
}

async function refresh() {
  const projectBeforeRefresh = store.selectedProjectId
  store.error = null
  await store.loadProjects()
  await syncRoute()
  if (projectBeforeRefresh && store.selectedProjectId === projectBeforeRefresh) {
    try { await store.reloadSelectedProject() } catch (cause) {
      store.error = formatError(cause, '无法刷新。', 'Unable to refresh')
    }
  }
}

function handleGlobalKeydown(event: KeyboardEvent) {
  if (showReview.value && event.key === 'Tab') {
    // A native confirmation dialog (for example promotion) owns the top-layer
    // focus cycle. Do not let the drawer-level trap steal its Tab events.
    if (document.querySelector('dialog[open]')) return
    const root = reviewDrawer.value
    if (!root) return
    const focusable = Array.from(root.querySelectorAll<HTMLElement>('button, a[href], input, textarea, select, summary, [contenteditable="true"], [tabindex]:not([tabindex="-1"])'))
      .filter((item) => !item.hasAttribute('disabled') && item.offsetParent !== null)
    if (focusable.length) {
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (!root.contains(document.activeElement)) { event.preventDefault(); first.focus(); return }
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); return }
      if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); return }
    }
    return
  }
  if (event.key !== 'Escape') return
  if (showPromotionDialog.value) {
    if (!actionBusy.value) showPromotionDialog.value = false
    return
  }
  else if (showReview.value) void closeReview()
  else closeMobileNav()
}

function updateMobileState() {
  isMobile.value = window.matchMedia('(max-width: 860px)').matches
  if (!isMobile.value) mobileNavOpen.value = false
}

function openMobileNav() {
  mobileNavOpen.value = true
  void nextTick(() => document.querySelector<HTMLElement>('#offcanon-task-navigation .sidebar-close')?.focus())
}

function closeMobileNav() {
  mobileNavOpen.value = false
  void nextTick(() => {
    const button = document.querySelector<HTMLElement>('.thread-nav-button')
    if (button && button.offsetParent !== null) button.focus()
  })
}

watch(showReview, (open) => {
  if (open) {
    if (isMobile.value) workspaceContent.value?.setAttribute('inert', '')
    void nextTick(() => reviewClose.value?.focus())
  } else {
    workspaceContent.value?.removeAttribute('inert')
  }
})
watch(mobileNavOpen, (open) => {
  if (open) void nextTick(() => document.querySelector<HTMLElement>('#offcanon-task-navigation .sidebar-close')?.focus())
})

watch(() => route.fullPath, () => void syncRoute())
watch([mainActivityExperimentId, reviewActivityExperimentId], () => syncEventStreams())
watch(() => store.experiments.map((experiment) => `${experiment.id}:${experiment.status}:${experiment.version}`).join('|'), () => {
  syncEventStreams()
  syncStateReconciliation()
})
watch(() => store.selectedExperimentId, () => {
  // A promotion confirmation belongs to one experiment. Never leave it open
  // while the user navigates to another historical turn.
  showPromotionDialog.value = false
})
watch(() => store.selectedExperiment?.status, (status) => {
  // A background refresh or another tab can advance the experiment while the
  // confirmation dialog is open. Do not leave an apply action mounted for a
  // candidate that is no longer in the VERIFIED state.
  if (showPromotionDialog.value && status !== 'VERIFIED') showPromotionDialog.value = false
})

onMounted(async () => {
  // Pinia stores outlive this view. Clear the previous account's project and
  // experiment selection before loading the newly authenticated user's data.
  store.clearSelection()
  await store.loadProjects()
  initialized.value = true
  await syncRoute()
  // The Pinia store survives route changes, while this view's EventSource does
  // not. Reconnect explicitly when returning from Settings or another route.
  syncEventStreams()
  window.addEventListener('keydown', handleGlobalKeydown)
  updateMobileState()
  window.addEventListener('resize', updateMobileState)
})

onUnmounted(() => {
  disconnectAllEventStreams()
  window.removeEventListener('keydown', handleGlobalKeydown)
  window.removeEventListener('resize', updateMobileState)
  store.clearSelection()
})
</script>

<template>
  <div class="agent-shell">
    <header class="agent-topbar" :inert="showReview ? true : undefined" :aria-hidden="showReview ? 'true' : undefined">
      <button class="brand-lockup" :aria-label="text('打开项目列表', 'Open project list')" :title="text('打开项目列表', 'Open project list')" @click="router.push({ name: 'home' })">
        <span class="brand-mark">O</span>
        <span><strong>Offcanon</strong><small>{{ text('Coding Agent', 'Coding Agent') }}</small></span>
      </button>
       <div class="topbar-project" v-if="store.selectedProject"><span class="topbar-project-dot" /><span class="topbar-project-name">{{ store.selectedProject.name }}</span><button class="icon-button small" :disabled="submitting || actionBusy" :aria-label="text('编辑项目设置', 'Edit project settings')" :title="text('编辑项目设置', 'Edit project settings')" @click="editSelectedProject"><Edit3 :size="13" /></button></div>
      <div class="topbar-context">
        <span v-if="mainActivityExperimentId" class="connection-state" :class="mainStreamState"><span class="stream-dot" :class="mainStreamState" />{{ connectionLabel }}</span>
         <button v-if="store.selectedProject" class="icon-button small mobile-project-edit" :disabled="submitting || actionBusy" :aria-label="text('编辑项目设置', 'Edit project settings')" :title="text('编辑项目设置', 'Edit project settings')" @click="editSelectedProject"><Edit3 :size="14" /></button>
        <button class="icon-button" :aria-label="text('刷新', 'Refresh')" :title="text('刷新', 'Refresh')" :disabled="store.loading" @click="refresh"><RefreshCw :class="{ spin: store.loading }" :size="16" /></button>
        <button class="account-button" :aria-label="text('打开设置', 'Open settings')" :title="text('设置', 'Settings')" @click="openSettings"><span>{{ accountInitials }}</span><Settings :size="14" /></button>
      </div>
    </header>

    <div v-if="store.error" class="global-alert" :inert="showReview ? true : undefined" :aria-hidden="showReview ? 'true' : undefined" role="alert">
      <CircleDot :size="16" /><span>{{ store.error }}</span><button class="icon-button small" :aria-label="text('关闭错误', 'Dismiss error')" :title="text('关闭', 'Dismiss')" @click="store.error = null"><X :size="15" /></button>
    </div>

    <div v-if="store.selectedProject && store.promotionRecovery?.recoveryRequired" class="project-recovery-banner" :inert="showReview ? true : undefined" :aria-hidden="showReview ? 'true' : undefined" role="alert">
      <AlertTriangle :size="17" />
      <div>
        <strong>{{ text('项目需要恢复', 'Project recovery required') }}</strong>
        <span>{{ text('上一笔应用操作尚未完成，新的应用会被阻止。请先确认当前项目状态。', 'An earlier project update is unresolved, so new applications are blocked. Reconcile the current project state first.') }}</span>
      </div>
      <button class="button warning compact" :disabled="actionBusy" @click="reconcileProject"><RefreshCw :class="{ spin: actionBusy }" :size="14" />{{ text('恢复项目', 'Reconcile project') }}</button>
    </div>
    <div v-else-if="store.selectedProject && store.promotionRecoveryError" class="project-recovery-banner error" :inert="showReview ? true : undefined" :aria-hidden="showReview ? 'true' : undefined" role="alert">
      <AlertTriangle :size="17" />
      <div>
        <strong>{{ text('无法确认项目写回状态', 'Project write-back state is unknown') }}</strong>
        <span>{{ store.promotionRecoveryError }}</span>
      </div>
      <button class="button secondary compact" :disabled="store.loading" @click="refresh"><RefreshCw :class="{ spin: store.loading }" :size="14" />{{ text('重新检查', 'Check again') }}</button>
    </div>

    <div ref="workspaceContent" class="agent-frame" :class="{ 'nav-open': mobileNavOpen, 'no-project': !store.selectedProject }" :inert="showReview ? true : undefined" :aria-hidden="showReview ? 'true' : undefined">
      <button v-if="mobileNavOpen && store.selectedProject" class="agent-nav-scrim" :aria-label="text('关闭任务导航', 'Close task navigation')" @click="closeMobileNav" />
      <TaskSidebar
        id="offcanon-task-navigation"
        :aria-hidden="isMobile && !!store.selectedProject && !mobileNavOpen ? 'true' : undefined"
        :inert="isMobile && !!store.selectedProject && !mobileNavOpen ? true : undefined"
        :projects="store.projects"
        :sessions="store.sessions"
        :experiments="store.experiments"
        :selected-project-id="store.selectedProjectId"
        :selected-session-id="store.selectedSessionId"
        :loading="store.loading"
         @add-project="openProjectDialog"
        @select-project="openProject"
        @select-session="selectSession"
        @new-task="startNewTask"
        @close="closeMobileNav"
      />

      <AgentThread
        ref="threadRef"
        :aria-hidden="isMobile && mobileNavOpen ? 'true' : undefined"
        :inert="isMobile && mobileNavOpen ? true : undefined"
        :project="store.selectedProject"
        :session="store.activeSession"
        :experiments="activeExperiments"
        :selected-experiment-id="store.selectedExperimentId"
         :activity="mainActivity"
         :activity-experiment-id="mainActivityExperimentId"
         :activity-truncated="activityTruncatedFor(mainActivityExperimentId)"
         :event-warning="eventWarningFor(mainActivityExperimentId)"
         :stream-state="mainStreamState"
        :action-busy="actionBusy || submitting"
        :detail-loading="store.detailLoading"
        :promotion-preview="store.promotionPreview"
        :navigation-open="mobileNavOpen"
        @open-navigation="openMobileNav"
        @submit="submitTask"
        @new-task="startNewTask"
        @select="selectExperiment"
        @review="openReview"
        @open-settings="openSettings"
        @retry="retryExperiment"
        @reconnect="mainActivityExperimentId && connectEvents(mainActivityExperimentId, true)"
        @start="startExperiment"
        @cancel="cancelExperiment"
        @verify="verifyExperiment"
        @edit-project="openProjectSettings"
        @model-readiness="agentModelReady = $event"
      />
    </div>

    <Transition name="drawer">
      <div v-if="showReview && store.selectedExperiment" class="review-overlay">
        <div class="review-overlay-scrim" aria-hidden="true" @click="closeReview" />
        <aside ref="reviewDrawer" class="review-drawer" role="dialog" aria-modal="true" aria-labelledby="review-drawer-title">
          <header class="review-drawer-header">
            <div><span id="review-drawer-title">{{ text('任务审阅', 'TASK REVIEW') }}</span><strong>EXP-{{ store.selectedExperiment.id.slice(0, 8).toUpperCase() }}</strong></div>
            <button ref="reviewClose" class="icon-button" :aria-label="text('关闭审阅', 'Close review')" :title="text('关闭', 'Close')" @click="closeReview"><X :size="17" /></button>
          </header>
          <ExperimentReview
            :project="store.selectedProject"
            :experiment="store.selectedExperiment"
            :diff="store.diff"
            :evidence="store.evidence"
            :promotion-preview="store.promotionPreview"
            :promotion-outcome="store.promotionOutcome"
            :promotion-reconcile="store.promotionReconcile"
            :activity="reviewActivity"
            :stream-state="reviewStreamState"
            :event-warning="reviewEventWarning"
            :activity-truncated="activityTruncatedFor(reviewActivityExperimentId)"
            :model-ready="agentModelReady"
            :session-busy="selectedSessionBusy"
            :action-busy="actionBusy"
            :detail-loading="store.detailLoading"
            :detail-error="store.detailError"
            :evidence-error="store.evidenceError"
            :diff-error="store.diffError"
            :promotion-preview-error="store.promotionPreviewError"
            :promotion-recovery-error="store.promotionRecoveryError"
            @back="closeReview"
            @start="store.selectedExperiment && startExperiment(store.selectedExperiment.id)"
            @cancel="store.selectedExperiment && cancelExperiment(store.selectedExperiment.id)"
            @verify="store.selectedExperiment && verifyExperiment(store.selectedExperiment.id)"
            @edit-project="openProjectSettings"
            @promote="showPromotionDialog = true"
            @reconcile="reconcileSelected"
            @open-settings="openSettings"
            @retry="store.selectedExperiment && retryExperiment(store.selectedExperiment.id)"
            @reconnect="reviewActivityExperimentId && connectEvents(reviewActivityExperimentId, true)"
            @continue-task="continueFromReview"
          />
        </aside>
      </div>
    </Transition>

    <ProjectDialog v-if="showProjectDialog" :project="editingProject" :verified-result-count="verifiedResultCount" :busy="submitting" :error="store.error" @close="showProjectDialog = false; editingProject = null" @submit="submitProject" />
    <PromotionDialog
      v-if="showPromotionDialog && store.selectedExperiment && store.promotionPreview"
      :experiment="store.selectedExperiment"
      :preview="store.promotionPreview"
      :changed-file-count="store.diff.length"
      :deleted-file-count="store.diff.filter((item) => item.change === 'DELETED').length"
      :busy="actionBusy"
      @close="showPromotionDialog = false"
      @confirm="confirmPromotionDecision"
    />
  </div>
</template>
